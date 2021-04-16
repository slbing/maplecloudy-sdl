package com.maplecloudy.avro.mapreduce.output;

import java.io.IOException;
import java.util.Arrays;

import org.apache.avro.Schema;
import org.apache.avro.generic.GenericData;
import org.apache.avro.generic.GenericDatumReader;
import org.apache.avro.io.DatumReader;
import org.apache.hadoop.conf.Configuration;
import org.apache.hadoop.fs.FileSystem;
import org.apache.hadoop.fs.FileUtil;
import org.apache.hadoop.fs.Path;
import org.apache.hadoop.mapred.InvalidJobConfException;
import org.apache.hadoop.mapreduce.Job;
import org.apache.hadoop.mapreduce.JobContext;
import org.apache.hadoop.mapreduce.Partitioner;
import org.apache.hadoop.mapreduce.RecordWriter;
import org.apache.hadoop.mapreduce.TaskAttemptContext;
import org.apache.hadoop.mapreduce.lib.output.FileOutputFormat;
import org.apache.hadoop.mapreduce.lib.partition.HashPartitioner;

import com.maplecloudy.avro.io.MapAvroFile;
import com.maplecloudy.avro.io.Pair;
import com.maplecloudy.avro.mapreduce.AvroJob;
import com.maplecloudy.avro.mapreduce.ConfigSchemaData;
import com.maplecloudy.avro.reflect.ReflectDataEx;
import com.maplecloudy.avro.util.HadoopFSUtil;

public class AvroMapOutputFormat<K,V> extends ExtFileOutputFormat<K,V> {
  
  /** The configuration key for Avro deflate level. */
  public static final String DEFLATE_LEVEL_KEY = "avro.mapred.deflate.level";
  
  /**
   * Enable output compression using the deflate codec and specify its level.
   */
  public static void setDeflateLevel(Job job, int level) {
    FileOutputFormat.setCompressOutput(job, true);
    job.getConfiguration().setInt(DEFLATE_LEVEL_KEY, level);
  }
  
  @Override
  public void checkOutputSpecs(JobContext job) throws IOException {
    // Ensure that the output directory is set and not already there
    Path outDir = getOutputPath(job);
    if (outDir == null) {
      throw new InvalidJobConfException("Output directory not set.");
    }
  }
  
  @Override
  public RecordWriter<K,V> getRecordWriter(TaskAttemptContext job)
      throws IOException, InterruptedException {
    
    Path path = this.getDefaultWorkFile(job, "");
    Schema schema = AvroJob.getMapOutputSchema(job.getConfiguration());
    Schema keySchema;
    Schema valueSchema;
    if (schema != null) {
      keySchema = Pair.getKeySchema(schema);
      valueSchema = Pair.getValueSchema(schema);
    } else {
      if (ConfigSchemaData.class.isAssignableFrom(job.getOutputKeyClass())) {
        try {
          keySchema = ((ConfigSchemaData) job.getOutputKeyClass().newInstance())
              .getSchema(job.getConfiguration());
        } catch (Exception e) {
          throw new RuntimeException(e);
        }
      } else keySchema = ReflectDataEx.get().getSchema(job.getOutputKeyClass());
      
      if (ConfigSchemaData.class.isAssignableFrom(job.getOutputValueClass())) {
        try {
          valueSchema = ((ConfigSchemaData) job.getOutputValueClass()
              .newInstance()).getSchema(job.getConfiguration());
        } catch (Exception e) {
          throw new RuntimeException(e);
        }
      } else valueSchema = ReflectDataEx.get()
          .getSchema(job.getOutputValueClass());
    }
    int level = job.getConfiguration().getInt(DEFLATE_LEVEL_KEY,
        MapAvroFile.DEFAULT_DEFLATE_LEVEL);
    final MapAvroFile.Writer<K,V> writer = new MapAvroFile.Writer<K,V>(
        job.getConfiguration(), FileSystem.get(job.getConfiguration()),
        path.toString(), keySchema, valueSchema, job, level);
    return new RecordWriter<K,V>() {
      
      @Override
      public void write(K key, V value) throws IOException {
        writer.append(key, value);
      }
      
      @Override
      public void close(TaskAttemptContext context)
          throws IOException, InterruptedException {
        writer.close();
      }
    };
  }
  
  /** Open the output generated by this format. */
  @SuppressWarnings("unchecked")
  public static <K,V> MapAvroFile.Reader<K,V>[] getReaders(Path dir,
      Configuration conf) throws IOException {
    FileSystem fs = dir.getFileSystem(conf);
    Path[] names = FileUtil
        .stat2Paths(fs.listStatus(dir, HadoopFSUtil.getPassJobDirFilter(fs)));
    
    // sort names, so that hash partitioning works
    Arrays.sort(names);
    
    MapAvroFile.Reader<K,V> parts[] = new MapAvroFile.Reader[names.length];
    for (int i = 0; i < names.length; i++) {
      try {
        MapAvroFile.Reader<K,V> part = new MapAvroFile.Reader<K,V>(fs,
            names[i].toString(), conf);
        // System.out.println("part " + i + " " + names[i].toString());
        parts[i] = part;
      } catch (Exception e) {
        e.printStackTrace();
        throw new IOException(e);
      }
    }
    return parts;
  }
  
  /** Open the output generated by this format. */
  @SuppressWarnings("unchecked")
  public static <K,V> MapAvroFile.Reader<K,V>[] getReaders(Path dir,
      FileSystem fs) throws IOException {
    Path[] names = FileUtil
        .stat2Paths(fs.listStatus(dir, HadoopFSUtil.getPassJobDirFilter(fs)));
    
    // sort names, so that hash partitioning works
    Arrays.sort(names);
    
    MapAvroFile.Reader<K,V> parts[] = new MapAvroFile.Reader[names.length];
    for (int i = 0; i < names.length; i++) {
      try {
        MapAvroFile.Reader<K,V> part = new MapAvroFile.Reader<K,V>(fs,
            names[i].toString(), fs.getConf());
        // System.out.println("part " + i + " " + names[i].toString());
        parts[i] = part;
      } catch (Exception e) {
        e.printStackTrace();
        throw new IOException(e);
      }
    }
    return parts;
  }
  
  /** Get an entry from output generated by this class. */
  public static <K,V> V getEntry(MapAvroFile.Reader<K,V>[] readers,
      Partitioner<K,V> partitioner, K key, V value) throws IOException {
    int part = partitioner.getPartition(key, value, readers.length);
    // System.out.println("Partitioner to part " + part);
    return readers[part].get(key);
    // V v = null;
    // // = readers[part].get(key);
    // // if(v== null)
    // // {
    // for (int i = 0; i < readers.length; i++) {
    // v = readers[i].get(key);
    // if(v != null)
    // {
    // System.out.println("found key at part " + i);
    // return v;
    // }
    // }
    // // }
    // return v;
  }
  
  public static <K,V> V getEntry(MapAvroFile.Reader<K,V>[] readers, K key)
      throws IOException {
    if (readers == null || readers.length == 0) return null;
    
    int part = new HashPartitioner<K,V>().getPartition(key, null,
        readers.length);
    return readers[part].get(key);
  }
  
  public static void main(String[] args) throws Exception {
    
    if (args.length < 2) {
      System.err.println("Usage: AvroMapOutputFormat <mapdb> <key>");
      return;
    }
    Configuration conf = new Configuration();
    conf.setClass(MapAvroFile.Reader.DATUM_READER_CLASS,
        GenericDatumReader.class, DatumReader.class);
    System.out.println("mapdb:" + args[0]);
    System.out.println("key:" + args[1]);
    MapAvroFile.Reader[] readers = AvroMapOutputFormat
        .getReaders(new Path(args[0]), conf);
    Object obj = AvroMapOutputFormat.getEntry(readers, args[1]);
    System.out.println(GenericData.get().toString(obj));
    for (int i = 0; i < readers.length; i++) {
      try {
        readers[i].close();
      } catch (Exception e) {
        
      }
    }
  }
}