package com.netease.arctic.spark.test.suites.sql;

import com.netease.arctic.spark.SparkSQLProperties;
import com.netease.arctic.spark.sql.catalyst.plans.QueryWithConstraintCheckPlan;
import com.netease.arctic.spark.test.SparkTableTestBase;
import com.netease.arctic.spark.test.helper.RecordGenerator;
import org.apache.hadoop.hive.metastore.api.Table;
import org.apache.iceberg.Schema;
import org.apache.iceberg.data.Record;
import org.apache.iceberg.relocated.com.google.common.collect.Lists;
import org.apache.iceberg.types.Types;
import org.apache.spark.sql.Dataset;
import org.apache.spark.sql.Row;
import org.apache.spark.sql.catalyst.plans.logical.CreateTableAsSelect;
import org.apache.spark.sql.catalyst.plans.logical.CreateV2Table;
import org.apache.spark.sql.catalyst.plans.logical.LogicalPlan;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.platform.commons.util.StringUtils;

import java.util.List;
import java.util.stream.Stream;

public class TestArcticSessionCatalog extends SparkTableTestBase {

  private final String database = "session_database_test";
  private final String table = "test_tbl";

  Dataset<Row> rs;

  @Override
  public Dataset<Row> sql(String sqlText) {
    rs = super.sql(sqlText);
    return rs;
  }


  public static Stream<Arguments> testCreateTable() {
    return Stream.of(
        Arguments.arguments("arctic", true, ""),
        Arguments.arguments("arctic", false, "pt"),
        Arguments.arguments("arctic", true, "pt"),

        Arguments.arguments("parquet", false, "pt"),
        Arguments.arguments("parquet", false, "dt string")
    );
  }

  @ParameterizedTest(name = "{index} USING {0} WITH PK {1} PARTITIONED BY ({2})")
  @MethodSource
  public void testCreateTable(String provider, boolean pk, String pt) {

    String sqlText = "CREATE TABLE " + target() + "(" +
        " id INT, data string, pt string ";
    if (pk) {
      sqlText += ", PRIMARY KEY(id)";
    }
    sqlText += ") USING " + provider;

    if (StringUtils.isNotBlank(pt)) {
      sqlText += " PARTITIONED BY (" + pt + ")";
    }

    sql(sqlText);
    LogicalPlan plan = qe.optimizedPlan();


    if ("arctic".equalsIgnoreCase(provider)) {
      Assertions.assertTrue(tableExists());
      Assertions.assertTrue(plan instanceof CreateV2Table);
    }

    Table hiveTable = loadHiveTable();
    Assertions.assertNotNull(hiveTable);
  }


  static final Schema schema = new Schema(
      Types.NestedField.required(1, "id", Types.IntegerType.get()),
      Types.NestedField.required(2, "data", Types.StringType.get()),
      Types.NestedField.required(3, "pt", Types.StringType.get())
  );

  List<Record> source = Lists.newArrayList(
      RecordGenerator.newRecord(schema, 1, "111", "AAA"),
      RecordGenerator.newRecord(schema, 2, "222", "AAA"),
      RecordGenerator.newRecord(schema, 3, "333", "DDD"),
      RecordGenerator.newRecord(schema, 4, "444", "DDD"),
      RecordGenerator.newRecord(schema, 5, "555", "EEE"),
      RecordGenerator.newRecord(schema, 6, "666", "EEE")
  );

  public static Stream<Arguments> testCreateTableAsSelect() {
    return Stream.of(
        Arguments.arguments("arctic", true, "", true),
        Arguments.arguments("arctic", false, "pt", true),
        Arguments.arguments("arctic", true, "pt", false),

        Arguments.arguments("parquet", false, "pt", false),
        Arguments.arguments("parquet", false, "", false)
    );
  }


  @ParameterizedTest(name = "{index} USING {0} WITH PK {1} PARTITIONED BY ({2})")
  @MethodSource
  public void testCreateTableAsSelect(String provider, boolean pk, String pt, boolean duplicateCheck) {
    spark().conf().set(SparkSQLProperties.CHECK_SOURCE_DUPLICATES_ENABLE, duplicateCheck);
    createViewSource(schema, source);
    String sqlText = "CREATE TABLE " + target();
    if (pk) {
      sqlText += " PRIMARY KEY (id, pt) ";
    }
    sqlText += " USING " + provider + " ";
    if (StringUtils.isNotBlank(pt)) {
      sqlText += " PARTITIONED BY (" + pt + ")";
    }
    sqlText += " AS SELECT * FROM " + source();

    sql(sqlText);
    if ("arctic".equalsIgnoreCase(provider)) {
      Assertions.assertTrue(tableExists());
      LogicalPlan plan = qe.optimizedPlan();
      Assertions.assertTrue(plan instanceof CreateTableAsSelect);

      if (duplicateCheck && pk) {
        LogicalPlan query = ((CreateTableAsSelect) plan).query();
        Assertions.assertTrue(query instanceof QueryWithConstraintCheckPlan);
      }
    }

    Table hiveTable = loadHiveTable();
    Assertions.assertNotNull(hiveTable);
  }
}
