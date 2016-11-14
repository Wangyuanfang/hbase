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
package org.apache.hadoop.hbase.backup;

import java.io.IOException;
import java.net.URI;
import java.util.List;

import org.apache.commons.cli.CommandLine;
import org.apache.commons.cli.HelpFormatter;
import org.apache.commons.lang.StringUtils;
import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.apache.hadoop.conf.Configuration;
import org.apache.hadoop.fs.Path;
import org.apache.hadoop.hbase.HBaseConfiguration;
import org.apache.hadoop.hbase.TableName;
import org.apache.hadoop.hbase.backup.impl.BackupManager;
import org.apache.hadoop.hbase.backup.impl.BackupSystemTable;
import org.apache.hadoop.hbase.backup.impl.HBaseBackupAdmin;
import org.apache.hadoop.hbase.backup.util.BackupServerUtil;
import org.apache.hadoop.hbase.backup.util.LogUtils;
import org.apache.hadoop.hbase.backup.util.RestoreServerUtil;
import org.apache.hadoop.hbase.client.Connection;
import org.apache.hadoop.hbase.client.ConnectionFactory;
import org.apache.hadoop.hbase.util.AbstractHBaseTool;
import org.apache.hadoop.hbase.util.FSUtils;
import org.apache.hadoop.util.ToolRunner;
import org.apache.log4j.Level;
import org.apache.log4j.Logger;

public class RestoreDriver extends AbstractHBaseTool implements BackupRestoreConstants {

  private static final Log LOG = LogFactory.getLog(RestoreDriver.class);
  private CommandLine cmd;

  private static final String USAGE_STRING =
      "Usage: bin/hbase restore <backup_path> <backup_id> <table(s)> [options]\n"
          + "  backup_path     Path to a backup destination root\n"
          + "  backup_id       Backup image ID to restore"
          + "  table(s)        Comma-separated list of tables to restore";

  private static final String USAGE_FOOTER = "";

  protected RestoreDriver() throws IOException {
    init();
  }

  protected void init() throws IOException {
    // disable irrelevant loggers to avoid it mess up command output
    LogUtils.disableZkAndClientLoggers(LOG);
  }

  private int parseAndRun(String[] args) throws IOException {
    // Check if backup is enabled
    if (!BackupManager.isBackupEnabled(getConf())) {
      System.err.println("Backup is not enabled. To enable backup, "+
          "set \'hbase.backup.enabled'=true and restart "+
          "the cluster");
      return -1;
    }
    // enable debug logging
    Logger backupClientLogger = Logger.getLogger("org.apache.hadoop.hbase.backup");
    if (cmd.hasOption(OPTION_DEBUG)) {
      backupClientLogger.setLevel(Level.DEBUG);
    }

    // whether to overwrite to existing table if any, false by default
    boolean overwrite = cmd.hasOption(OPTION_OVERWRITE);
    if (overwrite) {
      LOG.debug("Found -overwrite option in restore command, "
          + "will overwrite to existing table if any in the restore target");
    }

    // whether to only check the dependencies, false by default
    boolean check = cmd.hasOption(OPTION_CHECK);
    if (check) {
      LOG.debug("Found -check option in restore command, "
          + "will check and verify the dependencies");
    }

    LOG.debug("Will automatically restore all the dependencies");

    // parse main restore command options
    String[] remainArgs = cmd.getArgs();
    if (remainArgs.length < 3 && !cmd.hasOption(OPTION_SET)
        || (cmd.hasOption(OPTION_SET) && remainArgs.length < 2)) {
      printToolUsage();
      return -1;
    }

    String backupRootDir = remainArgs[0];
    String backupId = remainArgs[1];
    String tables = null;
    String tableMapping =
        cmd.hasOption(OPTION_TABLE_MAPPING) ? cmd.getOptionValue(OPTION_TABLE_MAPPING) : null;
    try (final Connection conn = ConnectionFactory.createConnection(conf);
        BackupAdmin client = new HBaseBackupAdmin(conn);) {
      // Check backup set
      if (cmd.hasOption(OPTION_SET)) {
        String setName = cmd.getOptionValue(OPTION_SET);
        try {
          tables = getTablesForSet(conn, setName, conf);
        } catch (IOException e) {
          System.out.println("ERROR: " + e.getMessage() + " for setName=" + setName);
          printToolUsage();
          return -2;
        }
        if (tables == null) {
          System.out.println("ERROR: Backup set '" + setName
              + "' is either empty or does not exist");
          printToolUsage();
          return -3;
        }
      } else {
        tables = remainArgs[2];
      }

      TableName[] sTableArray = BackupServerUtil.parseTableNames(tables);
      TableName[] tTableArray = BackupServerUtil.parseTableNames(tableMapping);

      if (sTableArray != null && tTableArray != null
          && (sTableArray.length != tTableArray.length)) {
        System.out.println("ERROR: table mapping mismatch: " + tables + " : " + tableMapping);
        printToolUsage();
        return -4;
      }

      client.restore(RestoreServerUtil.createRestoreRequest(backupRootDir, backupId, check,
        sTableArray, tTableArray, overwrite));
    } catch (Exception e) {
      e.printStackTrace();
      return -5;
    }
    return 0;
  }

  private String getTablesForSet(Connection conn, String name, Configuration conf)
      throws IOException {
    try (final BackupSystemTable table = new BackupSystemTable(conn)) {
      List<TableName> tables = table.describeBackupSet(name);
      if (tables == null) return null;
      return StringUtils.join(tables, BackupRestoreConstants.TABLENAME_DELIMITER_IN_COMMAND);
    }
  }

  @Override
  protected void addOptions() {
    // define supported options
    addOptNoArg(OPTION_OVERWRITE, OPTION_OVERWRITE_DESC);
    addOptNoArg(OPTION_CHECK, OPTION_CHECK_DESC);
    addOptNoArg(OPTION_DEBUG, OPTION_DEBUG_DESC);
    addOptWithArg(OPTION_SET, OPTION_SET_RESTORE_DESC);
    addOptWithArg(OPTION_TABLE_MAPPING, OPTION_TABLE_MAPPING_DESC);
  }

  @Override
  protected void processOptions(CommandLine cmd) {
    this.cmd = cmd;
  }

  @Override
  protected int doWork() throws Exception {
    return parseAndRun(cmd.getArgs());
  }

  public static void main(String[] args) throws Exception {
    Configuration conf = HBaseConfiguration.create();
    Path hbasedir = FSUtils.getRootDir(conf);
    URI defaultFs = hbasedir.getFileSystem(conf).getUri();
    FSUtils.setFsDefault(conf, new Path(defaultFs));
    int ret = ToolRunner.run(conf, new RestoreDriver(), args);
    System.exit(ret);
  }

  @Override
  public int run(String[] args) throws IOException {
    if (conf == null) {
      LOG.error("Tool configuration is not initialized");
      throw new NullPointerException("conf");
    }

    CommandLine cmd;
    try {
      // parse the command line arguments
      cmd = parseArgs(args);
      cmdLineArgs = args;
    } catch (Exception e) {
      System.out.println("Error when parsing command-line arguments: " + e.getMessage());
      printToolUsage();
      return EXIT_FAILURE;
    }

    if (!sanityCheckOptions(cmd) || cmd.hasOption(SHORT_HELP_OPTION)
        || cmd.hasOption(LONG_HELP_OPTION)) {
      printToolUsage();
      return EXIT_FAILURE;
    }

    processOptions(cmd);

    int ret = EXIT_FAILURE;
    try {
      ret = doWork();
    } catch (Exception e) {
      LOG.error("Error running command-line tool", e);
      return EXIT_FAILURE;
    }
    return ret;
  }

  @Override
  protected boolean sanityCheckOptions(CommandLine cmd) {
    boolean success = true;
    for (String reqOpt : requiredOptions) {
      if (!cmd.hasOption(reqOpt)) {
        System.out.println("Required option -" + reqOpt + " is missing");
        success = false;
      }
    }
    return success;
  }

  protected void printToolUsage() throws IOException {
    System.out.println(USAGE_STRING);
    HelpFormatter helpFormatter = new HelpFormatter();
    helpFormatter.setLeftPadding(2);
    helpFormatter.setDescPadding(8);
    helpFormatter.setWidth(100);
    helpFormatter.setSyntaxPrefix("Options:");
    helpFormatter.printHelp(" ", null, options, USAGE_FOOTER);
  }
}
