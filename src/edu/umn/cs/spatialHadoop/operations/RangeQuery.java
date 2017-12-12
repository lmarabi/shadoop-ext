/***********************************************************************
* Copyright (c) 2015 by Regents of the University of Minnesota.
* All rights reserved. This program and the accompanying materials
* are made available under the terms of the Apache License, Version 2.0 which 
* accompanies this distribution and is available at
* http://www.opensource.org/licenses/apache2.0.php.
*
*************************************************************************/
package edu.umn.cs.spatialHadoop.operations;

import java.io.IOException;
import java.util.Vector;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.apache.hadoop.fs.FSDataOutputStream;
import org.apache.hadoop.fs.FileSystem;
import org.apache.hadoop.fs.Path;
import org.apache.hadoop.io.NullWritable;
import org.apache.hadoop.io.Text;
import org.apache.hadoop.io.Writable;
import org.apache.hadoop.mapred.ClusterStatus;
import org.apache.hadoop.mapred.Counters;
import org.apache.hadoop.mapred.Counters.Counter;
import org.apache.hadoop.mapred.FileSplit;
import org.apache.hadoop.mapred.InputSplit;
import org.apache.hadoop.mapred.JobClient;
import org.apache.hadoop.mapred.JobConf;
import org.apache.hadoop.mapred.LocalJobRunner;
import org.apache.hadoop.mapred.MapReduceBase;
import org.apache.hadoop.mapred.Mapper;
import org.apache.hadoop.mapred.OutputCollector;
import org.apache.hadoop.mapred.Reporter;
import org.apache.hadoop.mapred.RunningJob;
import org.apache.hadoop.mapred.Task;
import org.apache.hadoop.mapred.lib.NullOutputFormat;
import org.apache.hadoop.util.GenericOptionsParser;

import edu.umn.cs.spatialHadoop.OperationsParams;
import edu.umn.cs.spatialHadoop.core.GlobalIndex;
import edu.umn.cs.spatialHadoop.core.Partition;
import edu.umn.cs.spatialHadoop.core.RTree;
import edu.umn.cs.spatialHadoop.core.Rectangle;
import edu.umn.cs.spatialHadoop.core.ResultCollector;
import edu.umn.cs.spatialHadoop.core.Shape;
import edu.umn.cs.spatialHadoop.core.SpatialSite;
import edu.umn.cs.spatialHadoop.io.Text2;
import edu.umn.cs.spatialHadoop.mapred.BlockFilter;
import edu.umn.cs.spatialHadoop.mapred.DefaultBlockFilter;
import edu.umn.cs.spatialHadoop.mapred.RTreeInputFormat;
import edu.umn.cs.spatialHadoop.mapred.ShapeInputFormat;
import edu.umn.cs.spatialHadoop.mapred.ShapeIterInputFormat;
import edu.umn.cs.spatialHadoop.mapred.ShapeIterRecordReader;
import edu.umn.cs.spatialHadoop.mapred.SpatialRecordReader.ShapeIterator;
import edu.umn.cs.spatialHadoop.mapred.TextOutputFormat;
import edu.umn.cs.spatialHadoop.util.Parallel;
import edu.umn.cs.spatialHadoop.util.Parallel.RunnableRange;
import edu.umn.cs.spatialHadoop.util.ResultCollectorSynchronizer;

/**
 * Performs a range query over a spatial file.
 * @author Ahmed Eldawy
 *
 */
public class RangeQuery {
  /**Logger for RangeQuery*/
  private static final Log LOG = LogFactory.getLog(RangeQuery.class);
  
  /**
   * A filter function that selects partitions overlapping with a query range.
   * @author Ahmed Eldawy
   *
   */
  public static class RangeFilter extends DefaultBlockFilter {
    
    /**A shape that is used to filter input*/
    private Shape queryRange;
    
    @Override
    public void configure(JobConf job) {
      this.queryRange = OperationsParams.getShape(job, "rect");
    }
    
    @Override
    public void selectCells(GlobalIndex<Partition> gIndex,
        ResultCollector<Partition> output) {
      int numPartitions;
      if (gIndex.isReplicated()) {
        // Need to process all partitions to perform duplicate avoidance
        numPartitions = gIndex.rangeQuery(queryRange, output);
        LOG.info("Selected "+numPartitions+" partitions overlapping "+queryRange);
      } else {
        Rectangle queryRange = this.queryRange.getMBR();
        // Need to process only partitions on the perimeter of the query range
        // Partitions that are totally contained in query range should not be
        // processed and should be copied to output directly
        numPartitions = 0;
        for (Partition p : gIndex) {
          if (queryRange.contains(p)) {
            // TODO partitions totally contained in query range should be copied
            // to output directly

            // XXX Until hard links are supported, R-tree blocks are processed
            // similar to R+-tree
            output.collect(p);
            numPartitions++;
          } else if (p.isIntersected(queryRange)) {
            output.collect(p);
            numPartitions++;
          }
        }
        LOG.info("Selected "+numPartitions+" partitions on the perimeter of "+queryRange);
      }
    }
  }
  
  
  /**
   * The map function used for range query
   * @author eldawy
   *
   * @param <T>
   */
  public static class RangeQueryMap extends MapReduceBase implements
      Mapper<Rectangle, Writable, NullWritable, Shape> {
    /**A shape that is used to filter input*/
    private Shape queryShape;
    private Rectangle queryMbr;

    @Override
    public void configure(JobConf job) {
      super.configure(job);
      queryShape = OperationsParams.getShape(job, "rect");
      queryMbr = queryShape.getMBR();
    }
    
    private final NullWritable dummy = NullWritable.get();
    
    /**
     * Map function for non-indexed blocks
     */
    public void map(final Rectangle cellMbr, final Writable value,
        final OutputCollector<NullWritable, Shape> output, Reporter reporter)
            throws IOException {
      if (value instanceof Shape) {
        Shape shape = (Shape) value;
        Rectangle shapeMBR = shape.getMBR();
        if (shapeMBR != null && shapeMBR.isIntersected(queryMbr)
            && shape.isIntersected(queryShape)) {
          boolean report_result = false;
          if (cellMbr.isValid()) {
            // Check for duplicate avoidance using reference point technique
            double reference_x = Math.max(queryMbr.x1, shapeMBR.x1);
            double reference_y = Math.max(queryMbr.y1, shapeMBR.y1);
            report_result = cellMbr.contains(reference_x, reference_y);
          } else {
            // A heap block, report right away
            report_result = true;
          }
          
          if (report_result)
            output.collect(dummy, shape);
        }
      } else if (value instanceof RTree) {
        RTree<Shape> shapes = (RTree<Shape>) value;
        shapes.search(queryMbr, new ResultCollector<Shape>() {
          @Override
          public void collect(Shape shape) {
            try {
              boolean report_result = false;
              if (cellMbr.isValid()) {
                // Check for duplicate avoidance using reference point technique
                Rectangle intersection = queryMbr.getIntersection(shape.getMBR());
                report_result = cellMbr.contains(intersection.x1, intersection.y1);
              } else {
                // A heap block, report right away
                report_result = true;
              }
              if (report_result)
                output.collect(dummy, shape);
            } catch (IOException e) {
              e.printStackTrace();
            }
          }
        });
      }
    }
  }
  
  
  /**
   * The map function used for range query
   * @author eldawy
   *
   * @param <T>
   */
  public static class RangeQueryMapNoDupAvoidance extends MapReduceBase implements
      Mapper<Rectangle, Writable, NullWritable, Shape> {
    /**A shape that is used to filter input*/
    private Shape queryShape;
    private Rectangle queryMbr;

    @Override
    public void configure(JobConf job) {
      super.configure(job);
      queryShape = OperationsParams.getShape(job, "rect");
      queryMbr = queryShape.getMBR();
    }
    
    private final NullWritable dummy = NullWritable.get();
    
    /**
     * Map function for non-indexed blocks
     */
    public void map(final Rectangle cellMbr, final Writable value,
        final OutputCollector<NullWritable, Shape> output, Reporter reporter)
            throws IOException {
      if (value instanceof Shape) {
        Shape shape = (Shape) value;
        if (shape.isIntersected(queryShape)) {
          output.collect(dummy, shape);
        }
      } else if (value instanceof RTree) {
        RTree<Shape> shapes = (RTree<Shape>) value;
        shapes.search(queryMbr, new ResultCollector<Shape>() {
          @Override
          public void collect(Shape shape) {
            try {
              output.collect(dummy, shape);
            } catch (IOException e) {
              e.printStackTrace();
            }
          }
        });
      }
    }
  }
  
  public static RunningJob rangeQueryMapReduce(Path inFile, Path outFile,
      OperationsParams params) throws IOException {
    JobConf job = new JobConf(params, RangeQuery.class);
    boolean overwrite = params.is("overwrite");
    Shape shape = params.getShape("shape");
    FileSystem outFs = inFile.getFileSystem(job);
    Path outputPath = outFile;
    if (outputPath == null) {
      do {
        outputPath = new Path(inFile.getName()+
            ".rangequery_"+(int)(Math.random() * 1000000));
      } while (outFs.exists(outputPath));
      job.setBoolean("output", false); // Avoid writing the output
    } else {
      if (outFs.exists(outputPath)) {
        if (overwrite) {
          outFs.delete(outputPath, true);
        } else {
          throw new RuntimeException("Output path already exists and -overwrite flag is not set");
        }
      }
    }
    
    job.setJobName("RangeQuery");
    job.setClass(SpatialSite.FilterClass, RangeFilter.class, BlockFilter.class);

    ClusterStatus clusterStatus = new JobClient(job).getClusterStatus();
    job.setNumMapTasks(clusterStatus.getMaxMapTasks() * 5);
    job.setNumReduceTasks(0);
    job.setMapOutputKeyClass(NullWritable.class);
    job.setMapOutputValueClass(shape.getClass());
    FileSystem inFs = inFile.getFileSystem(job);
    // Decide which map function to use depending on how blocks are indexed
    // And also which input format to use
    if (SpatialSite.isRTree(inFs, inFile)) {
      // RTree indexed file
      LOG.info("Searching an RTree indexed file");
      job.setInputFormat(RTreeInputFormat.class);
    } else {
      // A file with no local index
      LOG.info("Searching a non local-indexed file");
      job.setInputFormat(ShapeInputFormat.class);
    }
    ShapeInputFormat.setInputPaths(job, inFile);
    
    GlobalIndex<Partition> gIndex = SpatialSite.getGlobalIndex(inFs, inFile);
    if (gIndex != null && gIndex.isReplicated())
      job.setMapperClass(RangeQueryMap.class);
    else
      job.setMapperClass(RangeQueryMapNoDupAvoidance.class);

    if (job.getBoolean("output", true)) {
      job.setOutputFormat(TextOutputFormat.class);
      TextOutputFormat.setOutputPath(job, outputPath);
    } else {
      job.setOutputFormat(NullOutputFormat.class);
    }
    

    if (OperationsParams.isLocal(job, inFile)) {
      // Enforce local execution if explicitly set by user or for small files
      job.set("mapred.job.tracker", "local");
      // Use multithreading too
      job.setInt(LocalJobRunner.LOCAL_MAX_MAPS, Runtime.getRuntime().availableProcessors());
    }

    // Submit the job
    if (!params.is("background")) {
      RunningJob runningJob = JobClient.runJob(job);
      
      return runningJob;
    } else {
      JobClient jc = new JobClient(job);
      return jc.submitJob(job);
    }
  }
  
  /**
   * Runs a range query on the local machine (no MapReduce) and the output is
   * streamed to the provided result collector. The query might run in parallel
   * which makes it necessary to design the result collector to accept parallel
   * calls to the method {@link ResultCollector#collect(Object)}.
   * You can use {@link ResultCollectorSynchronizer} to synchronize calls to
   * your ResultCollector if you cannot design yours to be thread safe.
   * @param inFile
   * @param params
   * @param output
   * @return
   * @throws IOException
   */
  public static <S extends Shape> long rangeQueryLocal(Path inPath,
      final Shape queryRange, final S shape,
      final OperationsParams params, final ResultCollector<S> output) throws IOException {
    // Set MBR of query shape in job configuration to work with the spatial filter
    OperationsParams.setShape(params, "rect", queryRange.getMBR());
    params.setClass(SpatialSite.FilterClass, RangeFilter.class, BlockFilter.class);
    final FileSystem inFS = inPath.getFileSystem(params);
    // 1- Split the input path/file to get splits that can be processed independently
    ShapeIterInputFormat inputFormat = new ShapeIterInputFormat();
    JobConf job = new JobConf(params);
    ShapeIterInputFormat.setInputPaths(job, inPath);
    final InputSplit[] splits = inputFormat.getSplits(job, Runtime.getRuntime().availableProcessors());
    
    // 2- Process splits in parallel
    Vector<Long> results = Parallel.forEach(splits.length, new RunnableRange<Long>() {
      @Override
      public Long run(int i1, int i2) {
        S privateShape = (S) shape.clone();
        long results = 0;
        for (int i = i1; i < i2; i++) {
          try {
            FileSplit fsplit = (FileSplit) splits[i];
            if (fsplit.getStart() == 0 && SpatialSite.isRTree(inFS, fsplit.getPath())) {
              // Handle an RTree
              RTree<S> rtree = SpatialSite.loadRTree(inFS, fsplit.getPath(), privateShape);
              results += rtree.search(queryRange, output);
              rtree.close();
            } else {
              // Handle a heap file
              ShapeIterRecordReader reader = new ShapeIterRecordReader(params, fsplit);
              reader.setShape(privateShape);
              Rectangle key = reader.createKey();
              ShapeIterator shapes = reader.createValue();
              while (reader.next(key, shapes)) {
                for (Shape s : shapes) {
                  if (queryRange.isIntersected(s)) {
                    results++;
                    if (output != null)
                      output.collect((S) s);
                  }
                }
              }
              reader.close();
            }
          } catch (IOException e) {
            LOG.error("Error processing split "+splits[i], e);
          }
        }
        return results;
      }
    });
    long totalResultSize = 0;
    for (long result : results)
      totalResultSize += result;
    return totalResultSize;
  }
  
  private static void printUsage() {
    System.out.println("Performs a range query on an input file");
    System.out.println("Parameters: (* marks required parameters)");
    System.out.println("<input file> - (*) Path to input file");
    System.out.println("<output file> - Path to output file");
    System.out.println("rect:<x1,y1,x2,y2> - (*) Query rectangle");
    System.out.println("-overwrite - Overwrite output file without notice");
    GenericOptionsParser.printGenericCommandUsage(System.out);
  }
  
  public static void main(String[] args) throws IOException {
    final OperationsParams params = new OperationsParams(new GenericOptionsParser(args));
    final Path[] paths = params.getPaths();
    if (paths.length <= 1 && !params.checkInput()) {
      printUsage();
      System.exit(1);
    }
    if (paths.length >= 2 && !params.checkInputOutput()) {
      printUsage();
      System.exit(1);
    }
    if (params.get("rect") == null) {
      System.err.println("You must provide a query range");
      printUsage();
      System.exit(1);
    }
    final Path inPath = params.getInputPath();
    final Path outPath = params.getOutputPath();
    final Rectangle[] queryRanges = params.getShapes("rect", new Rectangle());

    // All running jobs
    Vector<Long> resultsCounts = new Vector<Long>();
    Vector<RunningJob> jobs = new Vector<RunningJob>();
    Vector<Thread> threads = new Vector<Thread>();

    long t1 = System.currentTimeMillis();
    for (int i = 0; i < queryRanges.length; i++) {
      final OperationsParams queryParams = new OperationsParams(params);
      OperationsParams.setShape(queryParams, "rect", queryRanges[i]);
      if (OperationsParams.isLocal(new JobConf(queryParams), inPath)) {
        // Run in local mode
        final Rectangle queryRange = queryRanges[i];
        final Shape shape = queryParams.getShape("shape");
        final Path output = outPath == null ? null :
          (queryRanges.length == 1 ? outPath : new Path(outPath, String.format("%05d", i)));
        Thread thread = new Thread() {
          @Override
          public void run() {
            try {
              ResultCollector<Shape> collector = null;
              if (output != null) {
                FileSystem outFS = output.getFileSystem(queryParams);
                final FSDataOutputStream outFile = outFS.create(output);
                final Text tempText = new Text2();
                collector = new ResultCollector<Shape>() {
                  @Override
                  public void collect(Shape r) {
                    try {
                      tempText.clear();
                      r.toText(tempText);
                      outFile.write(tempText.getBytes(), 0, tempText.getLength());
                    } catch (IOException e) {
                      e.printStackTrace();
                    }
                  }
                };
              }
              rangeQueryLocal(inPath, queryRange, shape, queryParams, collector);
            } catch (IOException e) {
              e.printStackTrace();
            }
          }
        };
        thread.start();
        threads.add(thread);
      } else {
        // Run in MapReduce mode
        queryParams.setBoolean("background", true);
        RunningJob job = rangeQueryMapReduce(inPath, outPath, queryParams);
        jobs.add(job);
      }
    }

    while (!jobs.isEmpty()) {
      RunningJob firstJob = jobs.firstElement();
      firstJob.waitForCompletion();
      if (!firstJob.isSuccessful()) {
        System.err.println("Error running job "+firstJob);
        System.err.println("Killing all remaining jobs");
        for (int j = 1; j < jobs.size(); j++)
          jobs.get(j).killJob();
        System.exit(1);
      }
      Counters counters = firstJob.getCounters();
      Counter outputRecordCounter = counters.findCounter(Task.Counter.MAP_OUTPUT_RECORDS);
      resultsCounts.add(outputRecordCounter.getValue());
      jobs.remove(0);
    }
    while (!threads.isEmpty()) {
      try {
        Thread thread = threads.firstElement();
        thread.join();
        threads.remove(0);
      } catch (InterruptedException e) {
        e.printStackTrace();
      }
    }
    long t2 = System.currentTimeMillis();
    
    System.out.println("Time for "+queryRanges.length+" jobs is "+(t2-t1)+" millis");
    System.out.println("Results counts: "+resultsCounts);
  }
}
