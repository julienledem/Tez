/**
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

package org.apache.hadoop.mapreduce.split;

import java.io.File;
import java.io.IOException;
import java.util.Arrays;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.apache.hadoop.classification.InterfaceAudience;
import org.apache.hadoop.classification.InterfaceStability;
import org.apache.hadoop.conf.Configuration;
import org.apache.hadoop.fs.FSDataInputStream;
import org.apache.hadoop.fs.FileStatus;
import org.apache.hadoop.fs.FileSystem;
import org.apache.hadoop.fs.Path;
import org.apache.hadoop.io.WritableUtils;
import org.apache.hadoop.mapreduce.split.JobSplit.TaskSplitMetaInfo;
import org.apache.tez.common.TezJobConfig;
import org.apache.tez.mapreduce.hadoop.MRJobConfig;

/**
 * A utility that reads the split meta info and creates split meta info objects
 */
@InterfaceAudience.Private
@InterfaceStability.Unstable
public class SplitMetaInfoReaderTez {

  public static final Log LOG = LogFactory.getLog(SplitMetaInfoReaderTez.class);

  public static final int META_SPLIT_VERSION = JobSplit.META_SPLIT_VERSION;
  public static final byte[] META_SPLIT_FILE_HEADER = JobSplit.META_SPLIT_FILE_HEADER;

  // Forked from the MR variant so that the metaInfo file as well as the split
  // file can be read from local fs - relying on these files being localized.
  public static TaskSplitMetaInfo[] readSplitMetaInfo(Configuration conf,
      FileSystem fs) throws IOException {

    long maxMetaInfoSize = conf.getLong(
        MRJobConfig.SPLIT_METAINFO_MAXSIZE,
        MRJobConfig.DEFAULT_SPLIT_METAINFO_MAXSIZE);

    Path metaSplitFile = new Path(
        conf.get(TezJobConfig.TASK_LOCAL_RESOURCE_DIR),
        MRJobConfig.JOB_SPLIT_METAINFO);
    String jobSplitFile = MRJobConfig.JOB_SPLIT;
    
    File file = new File(metaSplitFile.toUri().getPath()).getAbsoluteFile();
    LOG.info("DEBUG: Setting up JobSplitIndex with JobSplitFile at: "
        + file.getAbsolutePath() + ", defaultFS from conf: "
        + FileSystem.getDefaultUri(conf));
    
    FileStatus fStatus = fs.getFileStatus(metaSplitFile);
    if (maxMetaInfoSize > 0 && fStatus.getLen() > maxMetaInfoSize) {
      throw new IOException("Split metadata size exceeded " + maxMetaInfoSize
          + ". Aborting job ");
    }
    FSDataInputStream in = fs.open(metaSplitFile);
    byte[] header = new byte[JobSplit.META_SPLIT_FILE_HEADER.length];
    in.readFully(header);
    if (!Arrays.equals(JobSplit.META_SPLIT_FILE_HEADER, header)) {
      throw new IOException("Invalid header on split file");
    }
    int vers = WritableUtils.readVInt(in);
    if (vers != JobSplit.META_SPLIT_VERSION) {
      in.close();
      throw new IOException("Unsupported split version " + vers);
    }
    int numSplits = WritableUtils.readVInt(in); // TODO: check for insane values
    JobSplit.TaskSplitMetaInfo[] allSplitMetaInfo = new JobSplit.TaskSplitMetaInfo[numSplits];
    for (int i = 0; i < numSplits; i++) {
      JobSplit.SplitMetaInfo splitMetaInfo = new JobSplit.SplitMetaInfo();
      splitMetaInfo.readFields(in);
      JobSplit.TaskSplitIndex splitIndex = new JobSplit.TaskSplitIndex(
          new Path(conf.get(TezJobConfig.TASK_LOCAL_RESOURCE_DIR), jobSplitFile)
              .toUri().toString(), splitMetaInfo.getStartOffset());
      allSplitMetaInfo[i] = new JobSplit.TaskSplitMetaInfo(splitIndex,
          splitMetaInfo.getLocations(), splitMetaInfo.getInputDataLength());
    }
    in.close();
    return allSplitMetaInfo;
  }
}
