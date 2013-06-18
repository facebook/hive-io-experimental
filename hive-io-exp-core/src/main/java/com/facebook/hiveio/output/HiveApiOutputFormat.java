/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.facebook.hiveio.output;

import org.apache.hadoop.conf.Configuration;
import org.apache.hadoop.fs.FileSystem;
import org.apache.hadoop.fs.Path;
import org.apache.hadoop.hive.metastore.api.FieldSchema;
import org.apache.hadoop.hive.metastore.api.StorageDescriptor;
import org.apache.hadoop.hive.metastore.api.Table;
import org.apache.hadoop.hive.metastore.api.ThriftHiveMetastore;
import org.apache.hadoop.hive.serde2.Serializer;
import org.apache.hadoop.hive.serde2.objectinspector.StructObjectInspector;
import org.apache.hadoop.io.NullWritable;
import org.apache.hadoop.io.Writable;
import org.apache.hadoop.io.WritableComparable;
import org.apache.hadoop.mapred.FileOutputFormat;
import org.apache.hadoop.mapred.JobConf;
import org.apache.hadoop.mapred.Reporter;
import org.apache.hadoop.mapreduce.JobContext;
import org.apache.hadoop.mapreduce.OutputCommitter;
import org.apache.hadoop.mapreduce.OutputFormat;
import org.apache.hadoop.mapreduce.TaskAttemptContext;
import org.apache.hadoop.util.ReflectionUtils;
import org.apache.thrift.TException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.facebook.hiveio.common.FileSystems;
import com.facebook.hiveio.common.HadoopUtils;
import com.facebook.hiveio.common.HiveUtils;
import com.facebook.hiveio.common.Inspectors;
import com.facebook.hiveio.common.ProgressReporter;
import com.facebook.hiveio.record.HiveWritableRecord;
import com.facebook.hiveio.schema.HiveTableSchema;
import com.facebook.hiveio.schema.HiveTableSchemaImpl;
import com.facebook.hiveio.schema.HiveTableSchemas;
import com.google.common.base.Preconditions;
import com.google.common.collect.Lists;

import java.io.IOException;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Hadoop compatible OutputFormat for writing to Hive.
 */
public class HiveApiOutputFormat
    extends OutputFormat<WritableComparable, HiveWritableRecord> {
  /** Default profile if none given */
  public static final String DEFAULT_PROFILE_ID = "output-profile";

  /** Logger */
  private static final Logger LOG = LoggerFactory.getLogger(HiveApiOutputFormat.class);

  /** Counter for the files created, so we would be able to get unique name for new files */
  private static final AtomicInteger CREATED_FILES_COUNTER = new AtomicInteger(0);

  /** Which profile to lookup */
  private String myProfileId = DEFAULT_PROFILE_ID;

  public String getMyProfileId() {
    return myProfileId;
  }

  public void setMyProfileId(String myProfileId) {
    this.myProfileId = myProfileId;
  }

  /**
   * Get table schema for this profile in the configuration.
   * @param conf Configuration to lookup in
   * @return HiveTableSchema
   */
  public HiveTableSchema getTableSchema(Configuration conf) {
    return HiveTableSchemas.get(conf, myProfileId);
  }

  /**
   * Initialize using object's profile ID with Configuration and output
   * description passed in.
   * @param conf Configuration to use
   * @param outputDesc HiveOutputDescription
   * @throws TException Hive Metastore issues
   */
  public void init(Configuration conf, HiveOutputDescription outputDesc)
    throws TException {
    initProfile(conf, outputDesc, myProfileId);
  }

  /**
   * Initialize with default profile ID using Configuration and output
   * description passsed in.
   * @param conf Configuration to use
   * @param outputDesc HiveOutputDescription
   * @throws TException Hive Metastore issues
   */
  public static void initDefaultProfile(Configuration conf,
    HiveOutputDescription outputDesc) throws TException {
    initProfile(conf, outputDesc, DEFAULT_PROFILE_ID);
  }

  /**
   * Initialize passed in profile ID with Configuration and output description
   * passed in.
   * @param conf Configuration to use
   * @param outputDesc HiveOutputDescription
   * @param profileId Profile to use
   * @throws TException Hive Metastore issues
   */
  public static void initProfile(Configuration conf,
                                 HiveOutputDescription outputDesc,
                                 String profileId)
    throws TException {
    String dbName = outputDesc.getDbName();
    String tableName = outputDesc.getTableName();

    ThriftHiveMetastore.Iface client = outputDesc.metastoreClient(conf);

    Table table = client.get_table(dbName, tableName);
    sanityCheck(table, outputDesc);

    OutputInfo outputInfo = new OutputInfo(table);

    String partitionPiece;
    if (outputInfo.hasPartitionInfo()) {
      partitionPiece = HiveUtils.computePartitionPath(outputInfo.getPartitionInfo(),
          outputDesc.getPartitionValues());
    } else {
      partitionPiece = "_temp";
    }
    String partitionPath = outputInfo.getTableRoot() + Path.SEPARATOR + partitionPiece;

    outputInfo.setPartitionPath(partitionPath);
    HadoopUtils.setOutputDir(conf, partitionPath);

    if (outputInfo.hasPartitionInfo()) {
      outputInfo.setFinalOutputPath(outputInfo.getPartitionPath());
    } else {
      outputInfo.setFinalOutputPath(table.getSd().getLocation());
    }

    HiveTableSchema tableSchema = HiveTableSchemaImpl.fromTable(conf, table);
    HiveTableSchemas.put(conf, profileId, tableSchema);

    OutputConf outputConf = new OutputConf(conf, profileId);
    outputConf.writeOutputDescription(outputDesc);
    outputConf.writeOutputTableInfo(outputInfo);

    LOG.info("initProfile '{}' using {}", profileId, outputDesc);
  }

  /**
   * Check table is not misconfigured.
   * @param table Table to check
   * @param outputDesc HiveOutputDescription to use
   */
  private static void sanityCheck(Table table,
                                  HiveOutputDescription outputDesc) {
    StorageDescriptor sd = table.getSd();
    Preconditions.checkArgument(!sd.isCompressed());
    Preconditions.checkArgument(nullOrEmpty(sd.getBucketCols()));
    Preconditions.checkArgument(nullOrEmpty(sd.getSortCols()));
    Preconditions.checkArgument(table.getPartitionKeysSize() ==
        outputDesc.numPartitionValues());
  }

  /**
   * Check if collection is null or empty
   * @param <X> data type
   * @param c Collection to check
   * @return true if collection is null or empty
   */
  private static <X> boolean nullOrEmpty(Collection<X> c) {
    return c == null || c.isEmpty();
  }

  /**
   * Convert partition value map with ordered partition info into list of
   * partition values.
   * @param partitionValues Map of partition data
   * @param fieldSchemas List of partition column definitions
   * @return List<String> of partition values
   */
  private List<String> listOfPartitionValues(
    Map<String, String> partitionValues, List<FieldSchema> fieldSchemas) {
    List<String> values = Lists.newArrayList();
    for (FieldSchema fieldSchema : fieldSchemas) {
      String value = partitionValues.get(fieldSchema.getName().toLowerCase());
      values.add(value);
    }
    return values;
  }

  @Override
  public void checkOutputSpecs(JobContext jobContext)
    throws IOException, InterruptedException {
    Configuration conf = jobContext.getConfiguration();
    OutputConf outputConf = new OutputConf(conf, myProfileId);

    HiveOutputDescription description = outputConf.readOutputDescription();
    OutputInfo oti = outputConf.readOutputTableInfo();

    if (description == null || oti == null) {
      LOG.error("OutputConf information is null, nothing to check");
      return;
    }

    if (oti.hasPartitionInfo()) {
      if (!description.hasPartitionValues()) {
        throw new IOException("table is partitioned but user input isn't");
      }
      checkPartitionDoesntExist(conf, description, oti);
    } else {
      if (description.hasPartitionValues()) {
        throw new IOException("table is not partitioned but user input is");
      } else {
        checkTableIsEmpty(conf, description, oti);
      }
    }
  }

  /**
   * Check if the given table is empty, that is has no files
   * @param conf Configuration to use
   * @param description HiveOutputDescription
   * @param oti OutputInfo
   * @throws IOException Hadoop Filesystem issues
   */
  private void checkTableIsEmpty(Configuration conf,
    HiveOutputDescription description, OutputInfo oti)
    throws IOException {
    Path tablePath = new Path(oti.getTableRoot());
    FileSystem fs = tablePath.getFileSystem(conf);

    if (fs.exists(tablePath) && FileSystems.dirHasNonHiddenFiles(fs, tablePath)) {
      throw new IOException("Table " + description.getTableName() +
          " has existing data");
    }
  }

  /**
   * Check that partition we will be writing to does not already exist
   * @param conf Configuration to use
   * @param description HiveOutputDescription
   * @param oti OutputInfo
   * @throws IOException Hadoop Filesystem issues
   */
  private void checkPartitionDoesntExist(Configuration conf,
    HiveOutputDescription description, OutputInfo oti)
    throws IOException
  {
    ThriftHiveMetastore.Iface client;
    try {
      client = description.metastoreClient(conf);
    } catch (TException e) {
      throw new IOException(e);
    }

    String db = description.getDbName();
    String table = description.getTableName();

    if (oti.hasPartitionInfo()) {
      Map<String, String> partitionSpec = description.getPartitionValues();
      List<String> partitionValues = listOfPartitionValues(
          partitionSpec, oti.getPartitionInfo());

      if (partitionExists(client, db, table, partitionValues)) {
        throw new IOException("Table " + db + ":" + table + " partition " +
            partitionSpec + " already exists");
      }
    }
  }

  /**
   * Query Hive metastore if a table's partition exists already.
   * @param client Hive client
   * @param db Hive database name
   * @param table Hive table name
   * @param partitionValues list of partition values
   * @return true if partition exists
   */
  private boolean partitionExists(
      ThriftHiveMetastore.Iface client, String db, String table,
      List<String> partitionValues) {
    List<String> partitionNames;
    try {
      partitionNames = client.get_partition_names_ps(db, table,
          partitionValues, (short) 1);
      // CHECKSTYLE: stop IllegalCatch
    } catch (Exception e) {
      // CHECKSTYLE: resume IllegalCatch
      return false;
    }
    return !partitionNames.isEmpty();
  }

  @Override
  public RecordWriterImpl getRecordWriter(TaskAttemptContext taskAttemptContext)
    throws IOException, InterruptedException
  {
    HadoopUtils.setWorkOutputDir(taskAttemptContext);

    Configuration conf = taskAttemptContext.getConfiguration();
    OutputConf outputConf = new OutputConf(conf, myProfileId);

    OutputInfo oti = outputConf.readOutputTableInfo();

    HiveUtils.setRCileNumColumns(conf, oti.getColumnInfo().size());
    HadoopUtils.setOutputKeyWritableClass(conf, NullWritable.class);

    Serializer serializer = oti.createSerializer(conf);
    HadoopUtils.setOutputValueWritableClass(conf,
        serializer.getSerializedClass());

    org.apache.hadoop.mapred.OutputFormat baseOutputFormat =
        ReflectionUtils.newInstance(oti.getOutputFormatClass(), conf);
    // CHECKSTYLE: stop LineLength
    org.apache.hadoop.mapred.RecordWriter<WritableComparable, Writable> baseWriter =
        getBaseRecordWriter(taskAttemptContext, baseOutputFormat);
    // CHECKSTYLE: resume LineLength

    StructObjectInspector soi = Inspectors.createFor(oti.getColumnInfo());

    if (!outputConf.shouldResetSlowWrites()) {
      return new RecordWriterImpl(baseWriter, serializer, soi);
    } else {
      long writeTimeout = outputConf.getWriteResetTimeout();
      return new ResettableRecordWriterImpl(baseWriter, serializer, soi, taskAttemptContext,
          baseOutputFormat, writeTimeout);
    }
  }

  /**
   * Get the base Hadoop RecordWriter.
   * @param taskAttemptContext TaskAttemptContext
   * @param baseOutputFormat Hadoop OutputFormat
   * @return RecordWriter
   * @throws IOException Hadoop issues
   */
  // CHECKSTYLE: stop LineLengthCheck
  protected static org.apache.hadoop.mapred.RecordWriter<WritableComparable, Writable> getBaseRecordWriter(
    TaskAttemptContext taskAttemptContext,
    org.apache.hadoop.mapred.OutputFormat baseOutputFormat) throws IOException
  {
    // CHECKSTYLE: resume LineLengthCheck
    HadoopUtils.setWorkOutputDir(taskAttemptContext);
    JobConf jobConf = new JobConf(taskAttemptContext.getConfiguration());
    int fileId = CREATED_FILES_COUNTER.incrementAndGet();
    String name = FileOutputFormat.getUniqueName(jobConf, "part-" + fileId);
    Reporter reporter = new ProgressReporter(taskAttemptContext);
    org.apache.hadoop.mapred.RecordWriter<WritableComparable, Writable> baseWriter =
        baseOutputFormat.getRecordWriter(null, jobConf, name, reporter);
    LOG.info("getBaseRecordWriter: Created new {} with file {}", baseWriter, name);
    return baseWriter;
  }

  @Override
  public HiveApiOutputCommitter getOutputCommitter(
    TaskAttemptContext taskAttemptContext)
    throws IOException, InterruptedException
  {
    HadoopUtils.setWorkOutputDir(taskAttemptContext);
    Configuration conf = taskAttemptContext.getConfiguration();
    JobConf jobConf = new JobConf(conf);
    OutputCommitter baseCommitter = jobConf.getOutputCommitter();
    LOG.info("Getting output committer with base output committer {}",
        baseCommitter.getClass().getSimpleName());
    return new HiveApiOutputCommitter(baseCommitter, myProfileId);
  }
}
