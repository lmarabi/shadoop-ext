/***********************************************************************
* Copyright (c) 2015 by Regents of the University of Minnesota.
* All rights reserved. This program and the accompanying materials
* are made available under the terms of the Apache License, Version 2.0 which 
* accompanies this distribution and is available at
* http://www.opensource.org/licenses/apache2.0.php.
*
*************************************************************************/
package edu.umn.cs.spatialHadoop.nasa;

import java.awt.Color;
import java.awt.Point;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInput;
import java.io.DataOutput;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.zip.GZIPInputStream;
import java.util.zip.GZIPOutputStream;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;

import edu.umn.cs.spatialHadoop.core.Rectangle;
import edu.umn.cs.spatialHadoop.visualization.RasterLayer;

/**
 * A frequency map that can be used to draw a weighted heat map for NASA data
 * @author Ahmed Eldawy
 *
 */
public class HDFRasterLayer extends RasterLayer {
  @SuppressWarnings("unused")
  private static final Log LOG = LogFactory.getLog(HDFRasterLayer.class);
  
  /**Sum of temperatures*/
  protected long[][] sum;
  /**Count of temperatures*/
  protected long[][] count;

  /**The minimum value to be used while drawing the heat map*/
  private float min;

  /**The maximum value to be used while drawing the heat map*/
  private float max;
  
  /**
   * Initialize an empty frequency map to be used to deserialize 
   */
  public HDFRasterLayer() { }

  /**
   * Initializes a frequency map with the given dimensions
   * @param width
   * @param height
   */
  public HDFRasterLayer(Rectangle inputMBR, int width, int height) {
    this.inputMBR = inputMBR;
    this.width = width;
    this.height = height;
    this.sum = new long[width][height];
    this.count = new long[width][height];
    this.min = -1; this.max = -2;
  }
  
  /**
   * Sets the range of value to be used while drawing the heat map
   * @param min
   * @param max
   */
  public void setValueRange(float min, float max) {
    this.min = min;
    this.max = max;
  }
  
  @Override
  public void write(DataOutput out) throws IOException {
    super.write(out);
    ByteArrayOutputStream baos = new ByteArrayOutputStream();
    GZIPOutputStream gzos = new GZIPOutputStream(baos);
    ByteBuffer bbuffer = ByteBuffer.allocate(getHeight() * 2 * 8 + 8);
    bbuffer.putInt(getWidth());
    bbuffer.putInt(getHeight());
    gzos.write(bbuffer.array(), 0, bbuffer.position());
    for (int x = 0; x < getWidth(); x++) {
      bbuffer.clear();
      for (int y = 0; y < getHeight(); y++) {
        bbuffer.putLong(sum[x][y]);
        bbuffer.putLong(count[x][y]);
      }
      gzos.write(bbuffer.array(), 0, bbuffer.position());
    }
    gzos.close();
    
    byte[] serializedData = baos.toByteArray();
    out.writeInt(serializedData.length);
    out.write(serializedData);
  }
  
  @Override
  public void readFields(DataInput in) throws IOException {
    super.readFields(in);
    int length = in.readInt();
    byte[] serializedData = new byte[length];
    in.readFully(serializedData);
    ByteArrayInputStream bais = new ByteArrayInputStream(serializedData);
    GZIPInputStream gzis = new GZIPInputStream(bais);
    
    byte[] buffer = new byte[8];
    gzis.read(buffer);
    ByteBuffer bbuffer = ByteBuffer.wrap(buffer);
    int width = bbuffer.getInt();
    int height = bbuffer.getInt();
    // Reallocate memory only if needed
    if (width != this.getWidth() || height != this.getHeight()) {
      sum = new long[width][height];
      count = new long[width][height];
    }
    buffer = new byte[getHeight() * 2 * 8];
    for (int x = 0; x < getWidth(); x++) {
      int size = 0;
      while (size < buffer.length) {
        size += gzis.read(buffer, size, buffer.length - size);
      }
      bbuffer = ByteBuffer.wrap(buffer);
      for (int y = 0; y < getHeight(); y++) {
        sum[x][y] = bbuffer.getLong();
        count[x][y] = bbuffer.getLong();
      }
    }
  }
  
  public void mergeWith(HDFRasterLayer another) {
    Point offset = projectToImageSpace(another.getInputMBR().x1, another.getInputMBR().y1);
    int xmin = Math.max(0, offset.x);
    int ymin = Math.max(0, offset.y);
    int xmax = Math.min(this.getWidth(), another.getWidth() + offset.x);
    int ymax = Math.min(this.getHeight(), another.getHeight() + offset.y);
    for (int x = xmin; x < xmax; x++) {
      for (int y = ymin; y < ymax; y++) {
        this.sum[x][y] += another.sum[x - offset.x][y - offset.y];
        this.count[x][y] += another.count[x - offset.x][y - offset.y];
      }
    }
  }
  
  public BufferedImage asImage() {
    // Calculate the average
    float[][] avg = new float[getWidth()][getHeight()];
    for (int x = 0; x < this.getWidth(); x++) {
      for (int y = 0; y < this.getHeight(); y++) {
        avg[x][y] = (float)sum[x][y] / count[x][y];
      }
    }
    if (min >= max) {
      // Values not set. Autodetect
      min = Float.MAX_VALUE;
      max = -Float.MAX_VALUE;
      for (int x = 0; x < this.getWidth(); x++) {
        for (int y = 0; y < this.getHeight(); y++) {
          if (avg[x][y] < min)
            min = avg[x][y];
          if (avg[x][y] > max)
            max = avg[x][y];
        }
      }
    }
    BufferedImage image = new BufferedImage(getWidth(), getHeight(), BufferedImage.TYPE_INT_ARGB);
    for (int x = 0; x < this.getWidth(); x++) {
      for (int y = 0; y < this.getHeight(); y++) {
        Color color = calculateColor(avg[x][y], min, max);
        image.setRGB(x, y, color.getRGB());
      }
    }
    return image;
  }

  /**
   * Adds a point to the frequency map
   * @param cx
   * @param cy
   */
  public void addPoint(int cx, int cy, int weight) {
    if (cx >= 0 && cy >= 0 && cx < getWidth() && cy < getHeight()) {
      sum[cx][cy] += weight;
      count[cx][cy]++;
    }
  }

  public int getWidth() {
    return sum == null? 0 : sum.length;
  }

  public int getHeight() {
    return sum == null? 0 : sum[0].length;
  }
  
  /* The following methods are used to compute the gradient */

  protected Color[] colors;
  protected float[] hues;
  protected float[] saturations;
  protected float[] brightnesses;
  
  public enum GradientType {GT_HSB, GT_RGB};
  protected GradientType gradientType;
  
  public void setGradientInfor(Color color1, Color color2, GradientType gradientType) {
    this.colors = new Color[] {color1, color2};
    this.hues = new float[colors.length];
    this.saturations = new float[colors.length];
    this.brightnesses = new float[colors.length];
    
    for (int i = 0; i < colors.length; i++) {
      float[] hsbvals = new float[3];
      Color.RGBtoHSB(colors[i].getRed(), colors[i].getGreen(), colors[i].getBlue(), hsbvals);
      hues[i] = hsbvals[0];
      saturations[i] = hsbvals[1];
      brightnesses[i] = hsbvals[2];
    }
    this.gradientType = gradientType;
  }

  protected Color calculateColor(float value, float minValue, float maxValue) {
    Color color;
    if (value < minValue) {
      color = colors[0];
    } else if (value > maxValue) {
      color = colors[1];
    } else {
      // Interpolate between two colors according to gradient type
      float ratio = (value - minValue) / (maxValue - minValue);
      if (gradientType == GradientType.GT_HSB) {
        // Interpolate between two hues
        float hue = hues[0] * (1.0f - ratio) + hues[1] * ratio;
        float saturation = saturations[0] * (1.0f - ratio) + saturations[1] * ratio;
        float brightness = brightnesses[0] * (1.0f - ratio) + brightnesses[1] * ratio;
        color = Color.getHSBColor(hue, saturation, brightness);
        int alpha = (int) (colors[0].getAlpha() * (1.0f - ratio) + colors[1].getAlpha() * ratio);
        color = new Color(color.getRGB() & 0xffffff | (alpha << 24), true);
      } else if (gradientType == GradientType.GT_RGB) {
        // Interpolate between colors
        int red = (int) (colors[0].getRed() * (1.0f - ratio) + colors[1].getRed() * ratio);
        int green = (int) (colors[0].getGreen() * (1.0f - ratio) + colors[1].getGreen() * ratio);
        int blue = (int) (colors[0].getBlue() * (1.0f - ratio) + colors[1].getBlue() * ratio);
        int alpha = (int) (colors[0].getAlpha() * (1.0f - ratio) + colors[1].getAlpha() * ratio);
        color = new Color(red, green, blue, alpha);
      } else {
        throw new RuntimeException("Unsupported gradient type: "+gradientType);
      }
    }
    return color;
  }

}
