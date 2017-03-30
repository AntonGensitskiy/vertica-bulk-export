/*
 * Copyright © 2017 Cask Data, Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not
 * use this file except in compliance with the License. You may obtain a copy of
 * the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations under
 * the License.
 */

package co.cask.hydrator.plugin.db.batch.action;

import co.cask.cdap.api.annotation.Description;
import co.cask.cdap.api.annotation.Macro;
import co.cask.cdap.api.annotation.Name;
import co.cask.cdap.api.annotation.Plugin;
import co.cask.cdap.api.plugin.PluginConfig;
import co.cask.cdap.etl.api.action.Action;
import co.cask.cdap.etl.api.action.ActionContext;
import org.apache.commons.lang3.StringUtils;
import org.apache.hadoop.conf.Configuration;
import org.apache.hadoop.fs.FileSystem;
import org.apache.hadoop.fs.Path;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.BufferedWriter;
import java.io.OutputStreamWriter;
import java.sql.Connection;
import java.sql.Driver;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import javax.annotation.Nullable;

/**
 * Runs a select query after a pipeline run.
 */
@Plugin(type = Action.PLUGIN_TYPE)
@Name("VerticaBulkExportAction")
@Description("Vertica export plugin")
public class VerticaBulkExportAction extends Action {
  private static final Logger LOG = LoggerFactory.getLogger(VerticaBulkExportAction.class);
  private final VerticaConfig config;

  public VerticaBulkExportAction(VerticaConfig config) {
    this.config = config;
  }

  @Override
  public void run(ActionContext context) throws Exception {
    Object driver = Class.forName("com.vertica.jdbc.Driver").newInstance();
    DriverManager.registerDriver((Driver) driver);

    try (Connection connection = DriverManager.getConnection(config.connectionString, config.user, config.password)) {
      Statement statement = connection.createStatement();
      ResultSet resultSet = statement.executeQuery(config.selectStatement);
      ResultSetMetaData metadata = resultSet.getMetaData();
      int columnCount = metadata.getColumnCount();

      Configuration conf = new Configuration();
      FileSystem fileSystem = FileSystem.get(conf);
      Path exportFile = new Path(config.path);
      Path exportDir = exportFile.getParent();
      fileSystem.mkdirs(exportDir);
      BufferedWriter br = new BufferedWriter(new OutputStreamWriter(fileSystem.create(exportFile, false)));

      // write columns to file
      List<String> values = new ArrayList<>();
      for (int i = 1; i <= columnCount; i++) {
        values.add(metadata.getColumnName(i));
      }

      br.write(StringUtils.join(values, config.delimiter));
      br.newLine();

      while (resultSet.next()) {
        List<String> rowValues = new ArrayList<>();
        for (int i = 1; i <= columnCount; i++) {
          // if the column value is null, then use "null" string while writing to file
          if (resultSet.getString(i) == null) {
            rowValues.add("null");
          } else {
            rowValues.add(resultSet.getString(i));
          }
        }

        br.write(StringUtils.join(rowValues, config.delimiter));
        br.newLine();
      }

      br.close();
      DriverManager.deregisterDriver((Driver) driver);
    }
  }

  /**
   * Vertica config
   */
  public class VerticaConfig extends PluginConfig {
    public static final String CONNECTION_STRING = "connectionString";
    public static final String USER = "user";
    public static final String PASSWORD = "password";
    public static final String SELECTSTATEMENT = "selectStatement";
    public static final String DELIMITER = "delimiter";
    public static final String PATH = "path";

    @Name(CONNECTION_STRING)
    @Description("JDBC connection string including database name.")
    @Macro
    public String connectionString;

    @Name(USER)
    @Description("User to use to connect to the specified database. Required for databases that " +
      "need authentication. Optional for databases that do not require authentication.")
    @Nullable
    @Macro
    public String user;

    @Name(PASSWORD)
    @Description("Password to use to connect to the specified database. Required for databases that " +
      "need authentication. Optional for databases that do not require authentication.")
    @Nullable
    @Macro
    public String password;

    @Name(SELECTSTATEMENT)
    @Description("Select command to select values from a vertica table")
    @Macro
    @Nullable
    public String selectStatement;

    @Name(DELIMITER)
    @Description("Delimiter in the output file. Values in each column is separated by this delimiter while writing to" +
      " output file")
    @Nullable
    @Macro
    public String delimiter;

    @Name(PATH)
    @Description("HDFS File path where exported data will be written")
    @Macro
    public String path;

    public VerticaConfig(String connectionString, String user, String password, String selectStatement, String
      delimiter, String path) {
      this.connectionString = connectionString;
      this.user = user;
      this.password = password;
      this.selectStatement = selectStatement;
      this.delimiter = delimiter;
      this.path = path;
    }
  }
}
