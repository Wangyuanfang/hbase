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

import org.apache.hadoop.conf.Configurable;
import org.apache.hadoop.conf.Configuration;
import org.apache.hadoop.hbase.backup.impl.BackupManager;
import org.apache.hadoop.hbase.classification.InterfaceAudience;
import org.apache.hadoop.hbase.classification.InterfaceStability;

@InterfaceAudience.Private
@InterfaceStability.Evolving
public interface BackupCopyTask extends Configurable {

  /**
   * Copy backup data to destination
   * @param backupContext context object
   * @param backupManager backup manager
   * @param conf configuration
   * @param backupType backup type (FULL or INCREMENTAL)
   * @param options array of options (implementation-specific)
   * @return result (0 - success, -1 failure )
   * @throws IOException exception
   */
  int copy(BackupInfo backupContext, BackupManager backupManager, Configuration conf,
      BackupType copyType, String[] options) throws IOException;


   /**
    * Cancel copy job
    * @param jobHandler - copy job handler
    * @throws IOException
    */
   void cancelCopyJob(String jobHandler) throws IOException;
}
