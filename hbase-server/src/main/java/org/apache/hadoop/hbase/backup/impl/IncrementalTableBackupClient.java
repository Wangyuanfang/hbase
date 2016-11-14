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

package org.apache.hadoop.hbase.backup.impl;

import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;

import org.apache.commons.lang.StringUtils;
import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.apache.hadoop.conf.Configuration;
import org.apache.hadoop.fs.FileSystem;
import org.apache.hadoop.fs.Path;
import org.apache.hadoop.hbase.HConstants;
import org.apache.hadoop.hbase.TableName;
import org.apache.hadoop.hbase.backup.BackupCopyTask;
import org.apache.hadoop.hbase.backup.BackupInfo;
import org.apache.hadoop.hbase.backup.BackupInfo.BackupPhase;
import org.apache.hadoop.hbase.backup.BackupInfo.BackupState;
import org.apache.hadoop.hbase.backup.BackupRequest;
import org.apache.hadoop.hbase.backup.BackupRestoreServerFactory;
import org.apache.hadoop.hbase.backup.BackupType;
import org.apache.hadoop.hbase.backup.util.BackupClientUtil;
import org.apache.hadoop.hbase.backup.util.BackupServerUtil;
import org.apache.hadoop.hbase.classification.InterfaceAudience;
import org.apache.hadoop.hbase.client.Connection;

@InterfaceAudience.Private
public class IncrementalTableBackupClient {
  private static final Log LOG = LogFactory.getLog(IncrementalTableBackupClient.class);

  private Configuration conf;
  private Connection conn;
  //private String backupId;
  HashMap<String, Long> newTimestamps = null;

  private String backupId;
  private BackupManager backupManager;
  private BackupInfo backupContext;

  public IncrementalTableBackupClient() {
    // Required by the Procedure framework to create the procedure on replay
  }

  public IncrementalTableBackupClient(final Connection conn, final String backupId,
      BackupRequest request)
      throws IOException {

    this.conn = conn;
    this.conf = conn.getConfiguration();
    backupManager = new BackupManager(conn, conf);
    this.backupId = backupId;
    backupContext =
        backupManager.createBackupContext(backupId, BackupType.INCREMENTAL, request.getTableList(),
          request.getTargetRootDir(), request.getWorkers(), (int) request.getBandwidth());
  }

  private List<String> filterMissingFiles(List<String> incrBackupFileList) throws IOException {
    FileSystem fs = FileSystem.get(conf);
    List<String> list = new ArrayList<String>();
    for (String file : incrBackupFileList) {
      if (fs.exists(new Path(file))) {
        list.add(file);
      } else {
        LOG.warn("Can't find file: " + file);
      }
    }
    return list;
  }

  private List<String> getMissingFiles(List<String> incrBackupFileList) throws IOException {
    FileSystem fs = FileSystem.get(conf);
    List<String> list = new ArrayList<String>();
    for (String file : incrBackupFileList) {
      if (!fs.exists(new Path(file))) {
        list.add(file);
      }
    }
    return list;

  }

  /**
   * Do incremental copy.
   * @param backupContext backup context
   */
  private void incrementalCopy(BackupInfo backupContext) throws Exception {

    LOG.info("Incremental copy is starting.");
    // set overall backup phase: incremental_copy
    backupContext.setPhase(BackupPhase.INCREMENTAL_COPY);
    // get incremental backup file list and prepare parms for DistCp
    List<String> incrBackupFileList = backupContext.getIncrBackupFileList();
    // filter missing files out (they have been copied by previous backups)
    incrBackupFileList = filterMissingFiles(incrBackupFileList);
    String[] strArr = incrBackupFileList.toArray(new String[incrBackupFileList.size() + 1]);
    strArr[strArr.length - 1] = backupContext.getHLogTargetDir();

    BackupCopyTask copyService = BackupRestoreServerFactory.getBackupCopyTask(conf);
    int counter = 0;
    int MAX_ITERAIONS = 2;
    while (counter++ < MAX_ITERAIONS) {
      // We run DistCp maximum 2 times
      // If it fails on a second time, we throw Exception
      int res =
          copyService.copy(backupContext, backupManager, conf, BackupType.INCREMENTAL,
            strArr);

      if (res != 0) {
        LOG.error("Copy incremental log files failed with return code: " + res + ".");
        throw new IOException("Failed of Hadoop Distributed Copy from "
            + StringUtils.join(incrBackupFileList, ",") + " to "
            + backupContext.getHLogTargetDir());
      }
      List<String> missingFiles = getMissingFiles(incrBackupFileList);

      if (missingFiles.isEmpty()) {
        break;
      } else {
        // Repeat DistCp, some files have been moved from WALs to oldWALs during previous run
        // update backupContext and strAttr
        if (counter == MAX_ITERAIONS) {
          String msg =
              "DistCp could not finish the following files: "
          + StringUtils.join(missingFiles, ",");
          LOG.error(msg);
          throw new IOException(msg);
        }
        List<String> converted = convertFilesFromWALtoOldWAL(missingFiles);
        incrBackupFileList.removeAll(missingFiles);
        incrBackupFileList.addAll(converted);
        backupContext.setIncrBackupFileList(incrBackupFileList);

        // Run DistCp only for missing files (which have been moved from WALs to oldWALs
        // during previous run)
        strArr = converted.toArray(new String[converted.size() + 1]);
        strArr[strArr.length - 1] = backupContext.getHLogTargetDir();
      }
    }

    LOG.info("Incremental copy from " + StringUtils.join(incrBackupFileList, ",") + " to "
        + backupContext.getHLogTargetDir() + " finished.");
  }

  private List<String> convertFilesFromWALtoOldWAL(List<String> missingFiles) throws IOException {
    List<String> list = new ArrayList<String>();
    for (String path : missingFiles) {
      if (path.indexOf(Path.SEPARATOR + HConstants.HREGION_LOGDIR_NAME) < 0) {
        LOG.error("Copy incremental log files failed, file is missing : " + path);
        throw new IOException("Failed of Hadoop Distributed Copy to "
            + backupContext.getHLogTargetDir() + ", file is missing " + path);
      }
      list.add(path.replace(Path.SEPARATOR + HConstants.HREGION_LOGDIR_NAME, Path.SEPARATOR
          + HConstants.HREGION_OLDLOGDIR_NAME));
    }
    return list;
  }

  public void execute() throws IOException {

    // case PREPARE_INCREMENTAL:
    FullTableBackupClient.beginBackup(backupManager, backupContext);
    LOG.debug("For incremental backup, current table set is "
        + backupManager.getIncrementalBackupTableSet());
    try {
      IncrementalBackupManager incrBackupManager = new IncrementalBackupManager(backupManager);

      newTimestamps = incrBackupManager.getIncrBackupLogFileList(conn, backupContext);
    } catch (Exception e) {
      // fail the overall backup and return
      FullTableBackupClient.failBackup(conn, backupContext, backupManager, e,
        "Unexpected Exception : ", BackupType.INCREMENTAL, conf);
    }

    // case INCREMENTAL_COPY:
    try {
      // copy out the table and region info files for each table
      BackupServerUtil.copyTableRegionInfo(conn, backupContext, conf);
      incrementalCopy(backupContext);
      // Save list of WAL files copied
      backupManager.recordWALFiles(backupContext.getIncrBackupFileList());
    } catch (Exception e) {
      String msg = "Unexpected exception in incremental-backup: incremental copy " + backupId;
      // fail the overall backup and return
      FullTableBackupClient.failBackup(conn, backupContext, backupManager, e, msg,
        BackupType.INCREMENTAL, conf);
    }
    // case INCR_BACKUP_COMPLETE:
    // set overall backup status: complete. Here we make sure to complete the backup.
    // After this checkpoint, even if entering cancel process, will let the backup finished
    try {
      backupContext.setState(BackupState.COMPLETE);
      // Set the previousTimestampMap which is before this current log roll to the manifest.
      HashMap<TableName, HashMap<String, Long>> previousTimestampMap =
          backupManager.readLogTimestampMap();
      backupContext.setIncrTimestampMap(previousTimestampMap);

      // The table list in backupContext is good for both full backup and incremental backup.
      // For incremental backup, it contains the incremental backup table set.
      backupManager.writeRegionServerLogTimestamp(backupContext.getTables(), newTimestamps);

      HashMap<TableName, HashMap<String, Long>> newTableSetTimestampMap =
          backupManager.readLogTimestampMap();

      Long newStartCode =
          BackupClientUtil.getMinValue(BackupServerUtil
              .getRSLogTimestampMins(newTableSetTimestampMap));
      backupManager.writeBackupStartCode(newStartCode);
      // backup complete
      FullTableBackupClient.completeBackup(conn, backupContext, backupManager,
        BackupType.INCREMENTAL, conf);

    } catch (IOException e) {
      FullTableBackupClient.failBackup(conn, backupContext, backupManager, e,
        "Unexpected Exception : ", BackupType.INCREMENTAL, conf);
    }
  }

}
