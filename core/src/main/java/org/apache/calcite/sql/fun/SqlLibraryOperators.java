/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to you under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.apache.calcite.sql.fun;

import org.apache.calcite.rel.type.RelDataType;
import org.apache.calcite.rel.type.RelDataTypeFactory;
import org.apache.calcite.sql.SqlAggFunction;
import org.apache.calcite.sql.SqlCall;
import org.apache.calcite.sql.SqlFunction;
import org.apache.calcite.sql.SqlFunctionCategory;
import org.apache.calcite.sql.SqlKind;
import org.apache.calcite.sql.SqlNode;
import org.apache.calcite.sql.SqlOperator;
import org.apache.calcite.sql.SqlOperatorBinding;
import org.apache.calcite.sql.SqlOperatorTable;
import org.apache.calcite.sql.SqlSyntax;
import org.apache.calcite.sql.SqlWriter;
import org.apache.calcite.sql.type.InferTypes;
import org.apache.calcite.sql.type.OperandTypes;
import org.apache.calcite.sql.type.ReturnTypes;
import org.apache.calcite.sql.type.SameOperandTypeChecker;
import org.apache.calcite.sql.type.SqlOperandCountRanges;
import org.apache.calcite.sql.type.SqlReturnTypeInference;
import org.apache.calcite.sql.type.SqlTypeFamily;
import org.apache.calcite.sql.type.SqlTypeName;
import org.apache.calcite.sql.type.SqlTypeTransforms;
import org.apache.calcite.util.Optionality;

import com.google.common.collect.ImmutableList;

import org.checkerframework.checker.nullness.qual.Nullable;

import java.util.ArrayList;
import java.util.List;

import static org.apache.calcite.sql.fun.SqlLibrary.BIG_QUERY;
import static org.apache.calcite.sql.fun.SqlLibrary.HIVE;
import static org.apache.calcite.sql.fun.SqlLibrary.MSSQL;
import static org.apache.calcite.sql.fun.SqlLibrary.MYSQL;
import static org.apache.calcite.sql.fun.SqlLibrary.NETEZZA;
import static org.apache.calcite.sql.fun.SqlLibrary.ORACLE;
import static org.apache.calcite.sql.fun.SqlLibrary.POSTGRESQL;
import static org.apache.calcite.sql.fun.SqlLibrary.SNOWFLAKE;
import static org.apache.calcite.sql.fun.SqlLibrary.SPARK;
import static org.apache.calcite.sql.fun.SqlLibrary.STANDARD;
import static org.apache.calcite.sql.fun.SqlLibrary.TERADATA;
import static org.apache.calcite.sql.type.OperandTypes.DATETIME_INTEGER;
import static org.apache.calcite.sql.type.OperandTypes.DATETIME_INTERVAL;

/**
 * Defines functions and operators that are not part of standard SQL but
 * belong to one or more other dialects of SQL.
 *
 * <p>They are read by {@link SqlLibraryOperatorTableFactory} into instances
 * of {@link SqlOperatorTable} that contain functions and operators for
 * particular libraries.
 */
public abstract class SqlLibraryOperators {
  private SqlLibraryOperators() {
  }

  /** The "CONVERT_TIMEZONE(tz1, tz2, datetime)" function;
   * converts the timezone of {@code datetime} from {@code tz1} to {@code tz2}.
   * This function is only on Redshift, but we list it in PostgreSQL
   * because Redshift does not have its own library. */
  @LibraryOperator(libraries = {POSTGRESQL})
  public static final SqlFunction CONVERT_TIMEZONE =
      new SqlFunction("CONVERT_TIMEZONE",
          SqlKind.OTHER_FUNCTION,
          ReturnTypes.DATE_NULLABLE,
          null,
          OperandTypes.CHARACTER_CHARACTER_DATETIME,
          SqlFunctionCategory.TIMEDATE);

  /** Return type inference for {@code DECODE}. */
  private static final SqlReturnTypeInference DECODE_RETURN_TYPE =
      opBinding -> {
        final List<RelDataType> list = new ArrayList<>();
        for (int i = 1, n = opBinding.getOperandCount(); i < n; i++) {
          if (i < n - 1) {
            ++i;
          }
          list.add(opBinding.getOperandType(i));
        }
        final RelDataTypeFactory typeFactory = opBinding.getTypeFactory();
        RelDataType type = typeFactory.leastRestrictive(list);
        if (type != null && opBinding.getOperandCount() % 2 == 1) {
          type = typeFactory.createTypeWithNullability(type, true);
        }
        return type;
      };

  /** The "DECODE(v, v1, result1, [v2, result2, ...], resultN)" function. */
  @LibraryOperator(libraries = {ORACLE})
  public static final SqlFunction DECODE =
      new SqlFunction("DECODE", SqlKind.DECODE, DECODE_RETURN_TYPE, null,
          OperandTypes.VARIADIC, SqlFunctionCategory.SYSTEM);

  /** The "IF(condition, thenValue, elseValue)" function. */
  @LibraryOperator(libraries = {BIG_QUERY, HIVE, SPARK, SNOWFLAKE})
  public static final SqlFunction IF =
      new SqlFunction("IF", SqlKind.IF, SqlLibraryOperators::inferIfReturnType,
          null,
          OperandTypes.and(
              OperandTypes.family(SqlTypeFamily.BOOLEAN, SqlTypeFamily.ANY,
                  SqlTypeFamily.ANY),
              // Arguments 1 and 2 must have same type
              new SameOperandTypeChecker(3) {
                @Override protected List<Integer>
                getOperandList(int operandCount) {
                  return ImmutableList.of(1, 2);
                }
              }),
          SqlFunctionCategory.SYSTEM) {
        /***
         * Commenting this part as we create RexCall using this function
         */

//        @Override public boolean validRexOperands(int count, Litmus litmus) {
//          // IF is translated to RexNode by expanding to CASE.
//          return litmus.fail("not a rex operator");
//        }
      };

  /** Infers the return type of {@code IF(b, x, y)},
   * namely the least restrictive of the types of x and y.
   * Similar to {@link ReturnTypes#LEAST_RESTRICTIVE}. */
  private static @Nullable RelDataType inferIfReturnType(SqlOperatorBinding opBinding) {
    return opBinding.getTypeFactory()
        .leastRestrictive(opBinding.collectOperandTypes().subList(1, 3));
  }

  /** The "NVL(value, value)" function. */
  @LibraryOperator(libraries = {ORACLE, HIVE, SPARK})
  public static final SqlFunction NVL =
      new SqlFunction("NVL", SqlKind.NVL,
        ReturnTypes.LEAST_RESTRICTIVE
          .andThen(SqlTypeTransforms.TO_NULLABLE_ALL),
        null, OperandTypes.SAME_SAME, SqlFunctionCategory.SYSTEM);

  /** The "IFNULL(value, value)" function. */
  @LibraryOperator(libraries = {BIG_QUERY})
  public static final SqlFunction IFNULL =
      new SqlFunction("IFNULL", SqlKind.OTHER_FUNCTION,
          ReturnTypes.cascade(ReturnTypes.LEAST_RESTRICTIVE,
              SqlTypeTransforms.TO_NULLABLE_ALL),
          null, OperandTypes.SAME_SAME, SqlFunctionCategory.SYSTEM);

  /** The "ISNULL(value, value)" function. */
  @LibraryOperator(libraries = {MSSQL})
  public static final SqlFunction ISNULL =
      new SqlFunction("ISNULL", SqlKind.OTHER_FUNCTION,
          ReturnTypes.cascade(ReturnTypes.LEAST_RESTRICTIVE,
              SqlTypeTransforms.TO_NULLABLE_ALL),
          null, OperandTypes.SAME_SAME, SqlFunctionCategory.SYSTEM);

  /** The "LTRIM(string)" function. */
  @LibraryOperator(libraries = {ORACLE})
  public static final SqlFunction LTRIM =
      new SqlFunction("LTRIM", SqlKind.LTRIM,
          ReturnTypes.ARG0.andThen(SqlTypeTransforms.TO_NULLABLE)
              .andThen(SqlTypeTransforms.TO_VARYING), null,
          OperandTypes.STRING, SqlFunctionCategory.STRING);

  /** The "RTRIM(string)" function. */
  @LibraryOperator(libraries = {ORACLE})
  public static final SqlFunction RTRIM =
      new SqlFunction("RTRIM", SqlKind.RTRIM,
          ReturnTypes.ARG0.andThen(SqlTypeTransforms.TO_NULLABLE)
              .andThen(SqlTypeTransforms.TO_VARYING), null,
          OperandTypes.STRING, SqlFunctionCategory.STRING);

  /** BIG_QUERY's "SUBSTR(string, position [, substringLength ])" function. */
  @LibraryOperator(libraries = {BIG_QUERY})
  public static final SqlFunction SUBSTR_BIG_QUERY =
      new SqlFunction("SUBSTR", SqlKind.SUBSTR_BIG_QUERY,
          ReturnTypes.ARG0_NULLABLE_VARYING, null,
          OperandTypes.STRING_INTEGER_OPTIONAL_INTEGER,
          SqlFunctionCategory.STRING);

  /** MySQL's "SUBSTR(string, position [, substringLength ])" function. */
  @LibraryOperator(libraries = {MYSQL})
  public static final SqlFunction SUBSTR_MYSQL =
      new SqlFunction("SUBSTR", SqlKind.SUBSTR_MYSQL,
          ReturnTypes.ARG0_NULLABLE_VARYING, null,
          OperandTypes.STRING_INTEGER_OPTIONAL_INTEGER,
          SqlFunctionCategory.STRING);

  /** Oracle's "SUBSTR(string, position [, substringLength ])" function.
   *
   * <p>It has different semantics to standard SQL's
   * {@link SqlStdOperatorTable#SUBSTRING} function:
   *
   * <ul>
   *   <li>If {@code substringLength} &le; 0, result is the empty string
   *   (Oracle would return null, because it treats the empty string as null,
   *   but Calcite does not have these semantics);
   *   <li>If {@code position} = 0, treat {@code position} as 1;
   *   <li>If {@code position} &lt; 0, treat {@code position} as
   *       "length(string) + position + 1".
   * </ul>
   */
  @LibraryOperator(libraries = {ORACLE})
  public static final SqlFunction SUBSTR_ORACLE =
      new SqlFunction("SUBSTR", SqlKind.SUBSTR_ORACLE,
          ReturnTypes.ARG0_NULLABLE_VARYING, null,
          OperandTypes.STRING_INTEGER_OPTIONAL_INTEGER,
          SqlFunctionCategory.STRING);

  /** PostgreSQL's "SUBSTR(string, position [, substringLength ])" function. */
  @LibraryOperator(libraries = {POSTGRESQL})
  public static final SqlFunction SUBSTR_POSTGRESQL =
      new SqlFunction("SUBSTR", SqlKind.SUBSTR_POSTGRESQL,
          ReturnTypes.ARG0_NULLABLE_VARYING, null,
          OperandTypes.STRING_INTEGER_OPTIONAL_INTEGER,
          SqlFunctionCategory.STRING);

  /** The "GREATEST(value, value)" function. */
  @LibraryOperator(libraries = {ORACLE})
  public static final SqlFunction GREATEST =
      new SqlFunction("GREATEST", SqlKind.GREATEST,
        ReturnTypes.LEAST_RESTRICTIVE.andThen(
          SqlTypeTransforms.TO_NULLABLE), null,
        OperandTypes.SAME_VARIADIC, SqlFunctionCategory.SYSTEM);

  /** The "LEAST(value, value)" function. */
  @LibraryOperator(libraries = {ORACLE})
  public static final SqlFunction LEAST =
      new SqlFunction("LEAST", SqlKind.LEAST,
        ReturnTypes.LEAST_RESTRICTIVE.andThen(
          SqlTypeTransforms.TO_NULLABLE), null,
        OperandTypes.SAME_VARIADIC, SqlFunctionCategory.SYSTEM);

  /**
   * The <code>TRANSLATE(<i>string_expr</i>, <i>search_chars</i>,
   * <i>replacement_chars</i>)</code> function returns <i>string_expr</i> with
   * all occurrences of each character in <i>search_chars</i> replaced by its
   * corresponding character in <i>replacement_chars</i>.
   *
   * <p>It is not defined in the SQL standard, but occurs in Oracle and
   * PostgreSQL.
   */
  @LibraryOperator(libraries = {ORACLE, POSTGRESQL})
  public static final SqlFunction TRANSLATE3 = new SqlTranslate3Function();

  @LibraryOperator(libraries = {MYSQL})
  public static final SqlFunction JSON_TYPE = new SqlJsonTypeFunction();

  @LibraryOperator(libraries = {MYSQL})
  public static final SqlFunction JSON_DEPTH = new SqlJsonDepthFunction();

  @LibraryOperator(libraries = {MYSQL})
  public static final SqlFunction JSON_LENGTH = new SqlJsonLengthFunction();

  @LibraryOperator(libraries = {MYSQL})
  public static final SqlFunction JSON_KEYS = new SqlJsonKeysFunction();

  @LibraryOperator(libraries = {MYSQL})
  public static final SqlFunction JSON_PRETTY = new SqlJsonPrettyFunction();

  @LibraryOperator(libraries = {MYSQL})
  public static final SqlFunction JSON_REMOVE = new SqlJsonRemoveFunction();

  @LibraryOperator(libraries = {MYSQL})
  public static final SqlFunction JSON_STORAGE_SIZE = new SqlJsonStorageSizeFunction();

  @LibraryOperator(libraries = {MYSQL, ORACLE})
  public static final SqlFunction REGEXP_REPLACE = new SqlRegexpReplaceFunction();

  @LibraryOperator(libraries = {MYSQL})
  public static final SqlFunction COMPRESS =
      new SqlFunction("COMPRESS", SqlKind.OTHER_FUNCTION,
          ReturnTypes.explicit(SqlTypeName.VARBINARY)
              .andThen(SqlTypeTransforms.TO_NULLABLE),
          null, OperandTypes.STRING, SqlFunctionCategory.STRING);


  @LibraryOperator(libraries = {MYSQL})
  public static final SqlFunction EXTRACT_VALUE =
      new SqlFunction("EXTRACTVALUE", SqlKind.OTHER_FUNCTION,
          ReturnTypes.VARCHAR_2000.andThen(SqlTypeTransforms.FORCE_NULLABLE),
          null, OperandTypes.STRING_STRING, SqlFunctionCategory.SYSTEM);

  @LibraryOperator(libraries = {ORACLE})
  public static final SqlFunction XML_TRANSFORM =
      new SqlFunction("XMLTRANSFORM", SqlKind.OTHER_FUNCTION,
          ReturnTypes.VARCHAR_2000.andThen(SqlTypeTransforms.FORCE_NULLABLE),
          null, OperandTypes.STRING_STRING, SqlFunctionCategory.SYSTEM);

  @LibraryOperator(libraries = {ORACLE})
  public static final SqlFunction EXTRACT_XML =
      new SqlFunction("EXTRACT", SqlKind.OTHER_FUNCTION,
          ReturnTypes.VARCHAR_2000.andThen(SqlTypeTransforms.FORCE_NULLABLE),
          null, OperandTypes.STRING_STRING_OPTIONAL_STRING,
          SqlFunctionCategory.SYSTEM);

  @LibraryOperator(libraries = {ORACLE})
  public static final SqlFunction EXISTS_NODE =
      new SqlFunction("EXISTSNODE", SqlKind.OTHER_FUNCTION,
          ReturnTypes.INTEGER_NULLABLE
              .andThen(SqlTypeTransforms.FORCE_NULLABLE), null,
          OperandTypes.STRING_STRING_OPTIONAL_STRING, SqlFunctionCategory.SYSTEM);

  /** The "BOOL_AND(condition)" aggregate function, PostgreSQL and Redshift's
   * equivalent to {@link SqlStdOperatorTable#EVERY}. */
  @LibraryOperator(libraries = {POSTGRESQL})
  public static final SqlAggFunction BOOL_AND =
      new SqlMinMaxAggFunction("BOOL_AND", SqlKind.MIN, OperandTypes.BOOLEAN);

  /** The "BOOL_OR(condition)" aggregate function, PostgreSQL and Redshift's
   * equivalent to {@link SqlStdOperatorTable#SOME}. */
  @LibraryOperator(libraries = {POSTGRESQL})
  public static final SqlAggFunction BOOL_OR =
      new SqlMinMaxAggFunction("BOOL_OR", SqlKind.MAX, OperandTypes.BOOLEAN);

  /** The "LOGICAL_AND(condition)" aggregate function, BIG_QUERY's
   * equivalent to {@link SqlStdOperatorTable#EVERY}. */
  @LibraryOperator(libraries = {BIG_QUERY})
  public static final SqlAggFunction LOGICAL_AND =
      new SqlMinMaxAggFunction("LOGICAL_AND", SqlKind.MIN, OperandTypes.BOOLEAN);

  /** The "LOGICAL_OR(condition)" aggregate function, BIG_QUERY's
   * equivalent to {@link SqlStdOperatorTable#SOME}. */
  @LibraryOperator(libraries = {BIG_QUERY})
  public static final SqlAggFunction LOGICAL_OR =
      new SqlMinMaxAggFunction("LOGICAL_OR", SqlKind.MAX, OperandTypes.BOOLEAN);

  /** The "COUNTIF(condition) [OVER (...)]" function, in BIG_QUERY,
   * returns the count of TRUE values for expression.
   *
   * <p>{@code COUNTIF(b)} is equivalent to
   * {@code COUNT(*) FILTER (WHERE b)}. */
  @LibraryOperator(libraries = {BIG_QUERY})
  public static final SqlAggFunction COUNTIF =
      SqlBasicAggFunction
          .create(SqlKind.COUNTIF, ReturnTypes.BIGINT, OperandTypes.BOOLEAN)
          .withDistinct(Optionality.FORBIDDEN);

  /** The "ARRAY_AGG(value [ ORDER BY ...])" aggregate function,
   * in BIG_QUERY and PostgreSQL, gathers values into arrays. */
  @LibraryOperator(libraries = {POSTGRESQL, BIG_QUERY})
  public static final SqlAggFunction ARRAY_AGG =
      SqlBasicAggFunction
          .create(SqlKind.ARRAY_AGG,
              ReturnTypes.andThen(ReturnTypes::stripOrderBy,
                  ReturnTypes.TO_ARRAY), OperandTypes.ANY)
          .withFunctionType(SqlFunctionCategory.SYSTEM)
          .withSyntax(SqlSyntax.ORDERED_FUNCTION)
          .withAllowsNullTreatment(true);

  /** The "ARRAY_CONCAT_AGG(value [ ORDER BY ...])" aggregate function,
   * in BIG_QUERY and PostgreSQL, concatenates array values into arrays. */
  @LibraryOperator(libraries = {POSTGRESQL, BIG_QUERY})
  public static final SqlAggFunction ARRAY_CONCAT_AGG =
      SqlBasicAggFunction
          .create(SqlKind.ARRAY_CONCAT_AGG, ReturnTypes.ARG0,
              OperandTypes.ARRAY)
          .withFunctionType(SqlFunctionCategory.SYSTEM)
          .withSyntax(SqlSyntax.ORDERED_FUNCTION);

  /** The "STRING_AGG(value [, separator ] [ ORDER BY ...])" aggregate function,
   * BIG_QUERY and PostgreSQL's equivalent of
   * {@link SqlStdOperatorTable#LISTAGG}.
   *
   * <p>{@code STRING_AGG(v, sep ORDER BY x, y)} is implemented by
   * rewriting to {@code LISTAGG(v, sep) WITHIN GROUP (ORDER BY x, y)}. */
  @LibraryOperator(libraries = {POSTGRESQL, BIG_QUERY})
  public static final SqlAggFunction STRING_AGG =
      SqlBasicAggFunction
          .create(SqlKind.STRING_AGG, ReturnTypes.ARG0_NULLABLE,
              OperandTypes.or(OperandTypes.STRING, OperandTypes.STRING_STRING))
          .withFunctionType(SqlFunctionCategory.SYSTEM)
          .withSyntax(SqlSyntax.ORDERED_FUNCTION);

  /** The "DATE(string)" function, equivalent to "CAST(string AS DATE). */
  @LibraryOperator(libraries = {BIG_QUERY})
  public static final SqlFunction DATE =
      new SqlFunction("DATE", SqlKind.OTHER_FUNCTION,
          ReturnTypes.DATE_NULLABLE, null, OperandTypes.STRING,
          SqlFunctionCategory.TIMEDATE);

  /** The "CURRENT_DATETIME([timezone])" function. */
  @LibraryOperator(libraries = {BIG_QUERY})
  public static final SqlFunction CURRENT_DATETIME =
      new SqlFunction("CURRENT_DATETIME", SqlKind.OTHER_FUNCTION,
          ReturnTypes.TIMESTAMP.andThen(SqlTypeTransforms.TO_NULLABLE), null,
          OperandTypes.or(OperandTypes.NILADIC, OperandTypes.STRING),
          SqlFunctionCategory.TIMEDATE);

  /** The "DATE_FROM_UNIX_DATE(integer)" function; returns a DATE value
   * a given number of seconds after 1970-01-01. */
  @LibraryOperator(libraries = {BIG_QUERY})
  public static final SqlFunction DATE_FROM_UNIX_DATE =
      new SqlFunction("DATE_FROM_UNIX_DATE", SqlKind.OTHER_FUNCTION,
          ReturnTypes.DATE_NULLABLE, null, OperandTypes.INTEGER,
          SqlFunctionCategory.TIMEDATE);

  /** The "UNIX_DATE(date)" function; returns the number of days since
   * 1970-01-01. */
  @LibraryOperator(libraries = {BIG_QUERY})
  public static final SqlFunction UNIX_DATE =
      new SqlFunction("UNIX_DATE", SqlKind.OTHER_FUNCTION,
          ReturnTypes.INTEGER_NULLABLE, null, OperandTypes.DATE,
          SqlFunctionCategory.TIMEDATE);

  @LibraryOperator(libraries = {BIG_QUERY, HIVE, SPARK})
  public static final SqlFunction CURRENT_TIMESTAMP = new SqlCurrentTimestampFunction(
      "CURRENT_TIMESTAMP", SqlTypeName.TIMESTAMP);

  /**
   * The REGEXP_EXTRACT(source_string, regex_pattern) returns the first substring in source_string
   * that matches the regex_pattern. Returns NULL if there is no match.
   *
   * The REGEXP_EXTRACT_ALL(source_string, regex_pattern) returns an array of all substrings of
   * source_string that match the regex_pattern.
   */
  @LibraryOperator(libraries = {BIG_QUERY})
  public static final SqlFunction REGEXP_EXTRACT = new SqlFunction("REGEXP_EXTRACT",
        SqlKind.OTHER_FUNCTION,
        ReturnTypes.cascade(ReturnTypes.explicit(SqlTypeName.VARCHAR),
          SqlTypeTransforms.TO_NULLABLE),
      null, OperandTypes.family(
      ImmutableList.of(SqlTypeFamily.STRING, SqlTypeFamily.STRING,
          SqlTypeFamily.NUMERIC, SqlTypeFamily.NUMERIC),
          number -> number == 2 || number == 3),
      SqlFunctionCategory.STRING);

  @LibraryOperator(libraries = {BIG_QUERY})
  public static final SqlFunction REGEXP_EXTRACT_ALL = new SqlFunction("REGEXP_EXTRACT_ALL",
      SqlKind.OTHER_FUNCTION,
      ReturnTypes.cascade(ReturnTypes.explicit(SqlTypeName.VARCHAR),
        SqlTypeTransforms.TO_NULLABLE),
      null, OperandTypes.STRING_STRING,
      SqlFunctionCategory.STRING);

  @LibraryOperator(libraries = {BIG_QUERY})
  public static final SqlFunction FORMAT_TIMESTAMP = new SqlFunction("FORMAT_TIMESTAMP",
      SqlKind.OTHER_FUNCTION,
      ReturnTypes.VARCHAR_2000_NULLABLE, null,
      OperandTypes.family(SqlTypeFamily.STRING, SqlTypeFamily.TIMESTAMP),
      SqlFunctionCategory.TIMEDATE);

  @LibraryOperator(libraries = {HIVE, SPARK})
  public static final SqlFunction DATE_FORMAT = new SqlFunction("DATE_FORMAT",
      SqlKind.OTHER_FUNCTION,
      ReturnTypes.VARCHAR_2000_NULLABLE, null,
      OperandTypes.family(SqlTypeFamily.DATETIME, SqlTypeFamily.STRING),
      SqlFunctionCategory.TIMEDATE);

  @LibraryOperator(libraries = {STANDARD})
  public static final SqlFunction FORMAT_DATE = new SqlFunction("FORMAT_DATE",
      SqlKind.OTHER_FUNCTION,
      ReturnTypes.VARCHAR_2000_NULLABLE, null,
      OperandTypes.family(SqlTypeFamily.STRING, SqlTypeFamily.DATE),
      SqlFunctionCategory.TIMEDATE);

  @LibraryOperator(libraries = {STANDARD})
  public static final SqlFunction FORMAT_TIME = new SqlFunction("FORMAT_TIME",
      SqlKind.OTHER_FUNCTION,
      ReturnTypes.VARCHAR_2000_NULLABLE, null,
      OperandTypes.family(SqlTypeFamily.STRING, SqlTypeFamily.TIME),
      SqlFunctionCategory.TIMEDATE);

  @LibraryOperator(libraries = {BIG_QUERY})
  public static final SqlFunction TIME_ADD =
      new SqlFunction("TIME_ADD",
          SqlKind.PLUS,
          ReturnTypes.TIME, null,
          OperandTypes.family(SqlTypeFamily.ANY, SqlTypeFamily.TIME),
          SqlFunctionCategory.TIMEDATE) {

    @Override public void unparse(SqlWriter writer, SqlCall call, int leftPrec, int rightPrec) {
      writer.getDialect().unparseIntervalOperandsBasedFunctions(
          writer, call, leftPrec, rightPrec);
    }
  };

  @LibraryOperator(libraries = {BIG_QUERY})
  public static final SqlFunction INTERVAL_SECONDS = new SqlFunction("INTERVAL_SECONDS",
        SqlKind.OTHER_FUNCTION,
        ReturnTypes.INTEGER, null,
        OperandTypes.ANY, SqlFunctionCategory.TIMEDATE);

  /** The "MONTHNAME(datetime)" function; returns the name of the month,
   * in the current locale, of a TIMESTAMP or DATE argument. */
  @LibraryOperator(libraries = {MYSQL})
  public static final SqlFunction MONTHNAME =
      new SqlFunction("MONTHNAME", SqlKind.OTHER_FUNCTION,
          ReturnTypes.VARCHAR_2000, null, OperandTypes.DATETIME,
          SqlFunctionCategory.TIMEDATE);

  @LibraryOperator(libraries = {BIG_QUERY, HIVE, SPARK})
  public static final SqlFunction DATETIME_ADD =
      new SqlFunction("DATETIME_ADD",
      SqlKind.OTHER_FUNCTION,
      ReturnTypes.ARG0_NULLABLE,
      null,
      OperandTypes.DATETIME,
      SqlFunctionCategory.TIMEDATE) {

        @Override public void unparse(SqlWriter writer, SqlCall call, int leftPrec, int rightPrec) {
          writer.getDialect().unparseIntervalOperandsBasedFunctions(
              writer, call, leftPrec, rightPrec);
        }
      };

  @LibraryOperator(libraries = {BIG_QUERY})
  public static final SqlFunction DATETIME_SUB =
      new SqlFunction("DATETIME_SUB",
      SqlKind.OTHER_FUNCTION,
      ReturnTypes.ARG0_NULLABLE,
      null,
      OperandTypes.DATETIME,
      SqlFunctionCategory.TIMEDATE) {

        @Override public void unparse(SqlWriter writer, SqlCall call, int leftPrec, int rightPrec) {
          writer.getDialect().unparseIntervalOperandsBasedFunctions(
              writer, call, leftPrec, rightPrec);
        }
      };

  @LibraryOperator(libraries = {BIG_QUERY, HIVE, SPARK})
  public static final SqlFunction DATE_ADD =
      new SqlFunction(
        "DATE_ADD",
        SqlKind.PLUS,
        ReturnTypes.DATE,
        null,
        OperandTypes.or(DATETIME_INTERVAL, DATETIME_INTEGER),
        SqlFunctionCategory.TIMEDATE) {

      @Override public void unparse(SqlWriter writer, SqlCall call, int leftPrec, int rightPrec) {
        writer.getDialect().unparseIntervalOperandsBasedFunctions(
            writer, call, leftPrec, rightPrec);
      }
    };

  @LibraryOperator(libraries = {BIG_QUERY, HIVE, SPARK})
  public static final SqlFunction DATE_SUB =
      new SqlFunction(
        "DATE_SUB",
        SqlKind.MINUS,
        ReturnTypes.DATE,
        null,
        OperandTypes.or(DATETIME_INTERVAL, DATETIME_INTEGER),
        SqlFunctionCategory.TIMEDATE) {

        @Override public void unparse(SqlWriter writer, SqlCall call, int leftPrec, int rightPrec) {
          writer.getDialect().unparseIntervalOperandsBasedFunctions(
              writer, call, leftPrec, rightPrec);
        }
      };

  @LibraryOperator(libraries = {BIG_QUERY})
  public static final SqlFunction TIMESTAMP_ADD =
      new SqlFunction(
        "TIMESTAMP_ADD",
        SqlKind.PLUS,
        ReturnTypes.TIMESTAMP,
        null,
        OperandTypes.family(SqlTypeFamily.TIMESTAMP, SqlTypeFamily.DATETIME_INTERVAL),
        SqlFunctionCategory.TIMEDATE) {

        @Override public void unparse(SqlWriter writer, SqlCall call, int leftPrec, int rightPrec) {
          writer.getDialect().unparseIntervalOperandsBasedFunctions(
              writer, call, leftPrec, rightPrec);
        }
      };

  @LibraryOperator(libraries = {BIG_QUERY})
  public static final SqlFunction TIMESTAMP_SUB =
      new SqlFunction(
        "TIMESTAMP_SUB",
        SqlKind.MINUS,
        ReturnTypes.TIMESTAMP,
        null,
        OperandTypes.family(SqlTypeFamily.TIMESTAMP, SqlTypeFamily.DATETIME_INTERVAL),
        SqlFunctionCategory.TIMEDATE) {

        @Override public void unparse(SqlWriter writer, SqlCall call, int leftPrec, int rightPrec) {
          writer.getDialect().unparseIntervalOperandsBasedFunctions(
              writer, call, leftPrec, rightPrec);
        }
      };


  @LibraryOperator(libraries = {HIVE, SPARK, SNOWFLAKE, TERADATA})
  public static final SqlFunction ADD_MONTHS =
      new SqlFunction(
        "ADD_MONTHS",
        SqlKind.PLUS,
        ReturnTypes.ARG0,
        null,
        OperandTypes.family(SqlTypeFamily.DATETIME, SqlTypeFamily.INTEGER),
        SqlFunctionCategory.TIMEDATE);

  /** The "DAYNAME(datetime)" function; returns the name of the day of the week,
   * in the current locale, of a TIMESTAMP or DATE argument. */
  @LibraryOperator(libraries = {MYSQL})
  public static final SqlFunction DAYNAME =
      new SqlFunction("DAYNAME", SqlKind.OTHER_FUNCTION,
          ReturnTypes.VARCHAR_2000, null, OperandTypes.DATETIME,
          SqlFunctionCategory.TIMEDATE);

  @LibraryOperator(libraries = {MYSQL, POSTGRESQL})
  public static final SqlFunction LEFT =
      new SqlFunction("LEFT", SqlKind.OTHER_FUNCTION,
          ReturnTypes.ARG0_NULLABLE_VARYING, null,
          OperandTypes.CBSTRING_INTEGER, SqlFunctionCategory.STRING);

  @LibraryOperator(libraries = {MYSQL, POSTGRESQL})
  public static final SqlFunction REPEAT =
      new SqlFunction(
          "REPEAT",
          SqlKind.OTHER_FUNCTION,
          ReturnTypes.ARG0_NULLABLE_VARYING,
          null,
          OperandTypes.STRING_INTEGER,
          SqlFunctionCategory.STRING);

  @LibraryOperator(libraries = {MYSQL, POSTGRESQL})
  public static final SqlFunction RIGHT =
      new SqlFunction("RIGHT", SqlKind.OTHER_FUNCTION,
          ReturnTypes.ARG0_NULLABLE_VARYING, null,
          OperandTypes.CBSTRING_INTEGER, SqlFunctionCategory.STRING);

  @LibraryOperator(libraries = {MYSQL})
  public static final SqlFunction SPACE =
      new SqlFunction("SPACE",
          SqlKind.OTHER_FUNCTION,
          ReturnTypes.VARCHAR_2000_NULLABLE,
          null,
          OperandTypes.INTEGER,
          SqlFunctionCategory.STRING);

  @LibraryOperator(libraries = {MYSQL})
  public static final SqlFunction STRCMP =
      new SqlFunction("STRCMP",
          SqlKind.OTHER_FUNCTION,
          ReturnTypes.INTEGER_NULLABLE,
          null,
          OperandTypes.STRING_STRING,
          SqlFunctionCategory.STRING);

  @LibraryOperator(libraries = {MYSQL, POSTGRESQL, ORACLE})
  public static final SqlFunction SOUNDEX =
      new SqlFunction("SOUNDEX",
          SqlKind.OTHER_FUNCTION,
          ReturnTypes.VARCHAR_4_NULLABLE,
          null,
          OperandTypes.CHARACTER,
          SqlFunctionCategory.STRING);

  @LibraryOperator(libraries = {POSTGRESQL})
  public static final SqlFunction DIFFERENCE =
      new SqlFunction("DIFFERENCE",
          SqlKind.OTHER_FUNCTION,
          ReturnTypes.INTEGER_NULLABLE,
          null,
          OperandTypes.STRING_STRING,
          SqlFunctionCategory.STRING);

  /** The "CONCAT(arg, ...)" function that concatenates strings.
   * For example, "CONCAT('a', 'bc', 'd')" returns "abcd". */
  @LibraryOperator(libraries = {MYSQL, POSTGRESQL})
  public static final SqlFunction CONCAT_FUNCTION =
      new SqlFunction("CONCAT",
          SqlKind.OTHER_FUNCTION,
          ReturnTypes.MULTIVALENT_STRING_SUM_PRECISION_NULLABLE,
          InferTypes.RETURN_TYPE,
          OperandTypes.repeat(SqlOperandCountRanges.from(2),
              OperandTypes.STRING),
          SqlFunctionCategory.STRING);

  /** The "CONCAT(arg0, arg1)" function that concatenates strings.
   * For example, "CONCAT('a', 'bc')" returns "abc".
   *
   * <p>It is assigned {@link SqlKind#CONCAT2} to make it not equal to
   * {@link #CONCAT_FUNCTION}. */
  @LibraryOperator(libraries = {ORACLE})
  public static final SqlFunction CONCAT2 =
      new SqlFunction("CONCAT",
          SqlKind.CONCAT2,
          ReturnTypes.MULTIVALENT_STRING_SUM_PRECISION_NULLABLE,
          InferTypes.RETURN_TYPE,
          OperandTypes.STRING_SAME_SAME,
          SqlFunctionCategory.STRING);

  @LibraryOperator(libraries = {MYSQL})
  public static final SqlFunction REVERSE =
      new SqlFunction("REVERSE",
          SqlKind.REVERSE,
          ReturnTypes.ARG0_NULLABLE_VARYING,
          null,
          OperandTypes.CHARACTER,
          SqlFunctionCategory.STRING);

  @LibraryOperator(libraries = {MYSQL})
  public static final SqlFunction FROM_BASE64 =
      new SqlFunction("FROM_BASE64",
        SqlKind.OTHER_FUNCTION,
        ReturnTypes.explicit(SqlTypeName.VARBINARY)
          .andThen(SqlTypeTransforms.TO_NULLABLE),
        null,
        OperandTypes.STRING,
        SqlFunctionCategory.STRING);

  @LibraryOperator(libraries = {MYSQL})
  public static final SqlFunction TO_BASE64 =
      new SqlFunction("TO_BASE64",
        SqlKind.OTHER_FUNCTION,
        ReturnTypes.explicit(SqlTypeName.VARCHAR)
          .andThen(SqlTypeTransforms.TO_NULLABLE),
        null,
        OperandTypes.or(OperandTypes.STRING, OperandTypes.BINARY),
        SqlFunctionCategory.STRING);

  /** The "TO_DATE(string1, string2)" function; casts string1
   * to a DATE using the format specified in string2. */
  @LibraryOperator(libraries = {POSTGRESQL, ORACLE, SPARK})
  public static final SqlFunction TO_DATE =
      new SqlFunction("TO_DATE",
          SqlKind.OTHER_FUNCTION,
          ReturnTypes.DATE_NULLABLE,
          null,
          OperandTypes.STRING_STRING,
          SqlFunctionCategory.TIMEDATE);

  /** The "TO_TIMESTAMP(string1, string2)" function; casts string1
   * to a TIMESTAMP using the format specified in string2. */
  @LibraryOperator(libraries = {POSTGRESQL, ORACLE, SNOWFLAKE})
  public static final SqlFunction TO_TIMESTAMP =
      new SqlFunction("TO_TIMESTAMP",
          SqlKind.OTHER_FUNCTION,
          ReturnTypes.DATE_NULLABLE,
          null,
          OperandTypes.STRING_STRING,
          SqlFunctionCategory.TIMEDATE);

  /** The "TIMESTAMP_SECONDS(bigint)" function; returns a TIMESTAMP value
   * a given number of seconds after 1970-01-01 00:00:00. */
  @LibraryOperator(libraries = {BIG_QUERY})
  public static final SqlFunction TIMESTAMP_SECONDS =
      new SqlFunction("TIMESTAMP_SECONDS", SqlKind.OTHER_FUNCTION,
          ReturnTypes.TIMESTAMP_NULLABLE, null, OperandTypes.INTEGER,
          SqlFunctionCategory.TIMEDATE);

  /** The "TIMESTAMP_MILLIS(bigint)" function; returns a TIMESTAMP value
   * a given number of milliseconds after 1970-01-01 00:00:00. */
  @LibraryOperator(libraries = {BIG_QUERY})
  public static final SqlFunction TIMESTAMP_MILLIS =
      new SqlFunction("TIMESTAMP_MILLIS", SqlKind.OTHER_FUNCTION,
          ReturnTypes.TIMESTAMP_NULLABLE, null, OperandTypes.INTEGER,
          SqlFunctionCategory.TIMEDATE);

  /** The "TIMESTAMP_MICROS(bigint)" function; returns a TIMESTAMP value
   * a given number of micro-seconds after 1970-01-01 00:00:00. */
  @LibraryOperator(libraries = {BIG_QUERY})
  public static final SqlFunction TIMESTAMP_MICROS =
      new SqlFunction("TIMESTAMP_MICROS", SqlKind.OTHER_FUNCTION,
          ReturnTypes.TIMESTAMP_NULLABLE, null, OperandTypes.INTEGER,
          SqlFunctionCategory.TIMEDATE);

  /** The "UNIX_SECONDS(bigint)" function; returns the number of seconds
   * since 1970-01-01 00:00:00. */
  @LibraryOperator(libraries = {BIG_QUERY})
  public static final SqlFunction UNIX_SECONDS =
      new SqlFunction("UNIX_SECONDS", SqlKind.OTHER_FUNCTION,
          ReturnTypes.BIGINT_NULLABLE, null, OperandTypes.TIMESTAMP,
          SqlFunctionCategory.TIMEDATE);

  /** The "UNIX_MILLIS(bigint)" function; returns the number of milliseconds
   * since 1970-01-01 00:00:00. */
  @LibraryOperator(libraries = {BIG_QUERY})
  public static final SqlFunction UNIX_MILLIS =
      new SqlFunction("UNIX_MILLIS", SqlKind.OTHER_FUNCTION,
          ReturnTypes.BIGINT_NULLABLE, null, OperandTypes.TIMESTAMP,
          SqlFunctionCategory.TIMEDATE);

  /** The "UNIX_MICROS(bigint)" function; returns the number of microseconds
   * since 1970-01-01 00:00:00. */
  @LibraryOperator(libraries = {BIG_QUERY})
  public static final SqlFunction UNIX_MICROS =
      new SqlFunction("UNIX_MICROS", SqlKind.OTHER_FUNCTION,
          ReturnTypes.BIGINT_NULLABLE, null, OperandTypes.TIMESTAMP,
          SqlFunctionCategory.TIMEDATE);

  @LibraryOperator(libraries = {ORACLE})
  public static final SqlFunction CHR =
      new SqlFunction("CHR",
          SqlKind.OTHER_FUNCTION,
          ReturnTypes.CHAR,
          null,
          OperandTypes.INTEGER,
          SqlFunctionCategory.STRING);

  @LibraryOperator(libraries = {ORACLE})
  public static final SqlFunction TANH =
      new SqlFunction("TANH",
          SqlKind.OTHER_FUNCTION,
          ReturnTypes.DOUBLE_NULLABLE,
          null,
          OperandTypes.NUMERIC,
          SqlFunctionCategory.NUMERIC);

  @LibraryOperator(libraries = {ORACLE})
  public static final SqlFunction COSH =
      new SqlFunction("COSH",
          SqlKind.OTHER_FUNCTION,
          ReturnTypes.DOUBLE_NULLABLE,
          null,
          OperandTypes.NUMERIC,
          SqlFunctionCategory.NUMERIC);

  @LibraryOperator(libraries = {ORACLE})
  public static final SqlFunction SINH =
      new SqlFunction("SINH",
          SqlKind.OTHER_FUNCTION,
          ReturnTypes.DOUBLE_NULLABLE,
          null,
          OperandTypes.NUMERIC,
          SqlFunctionCategory.NUMERIC);

  @LibraryOperator(libraries = {MYSQL, POSTGRESQL})
  public static final SqlFunction MD5 =
      new SqlFunction("MD5",
        SqlKind.OTHER_FUNCTION,
        ReturnTypes.explicit(SqlTypeName.VARCHAR)
          .andThen(SqlTypeTransforms.TO_NULLABLE),
        null,
        OperandTypes.or(OperandTypes.STRING, OperandTypes.BINARY),
        SqlFunctionCategory.STRING);

  @LibraryOperator(libraries = {MYSQL, POSTGRESQL})
  public static final SqlFunction SHA1 =
      new SqlFunction("SHA1",
        SqlKind.OTHER_FUNCTION,
        ReturnTypes.explicit(SqlTypeName.VARCHAR)
          .andThen(SqlTypeTransforms.TO_NULLABLE),
        null,
        OperandTypes.or(OperandTypes.STRING, OperandTypes.BINARY),
        SqlFunctionCategory.STRING);

  /** Infix "::" cast operator used by PostgreSQL, for example
   * {@code '100'::INTEGER}. */
  @LibraryOperator(libraries = {POSTGRESQL})
  public static final SqlOperator INFIX_CAST =
      new SqlCastOperator();

  @LibraryOperator(libraries = {STANDARD})
  public static final SqlFunction FORMAT =
      new SqlFunction(
        "FORMAT",
        SqlKind.FORMAT,
        ReturnTypes.VARCHAR_2000_NULLABLE, null,
        OperandTypes.family(SqlTypeFamily.STRING, SqlTypeFamily.NUMERIC),
        SqlFunctionCategory.STRING);

  /** The "TO_NUMBER(string1, string2)" function; casts string1
   * as hexadecimal to a NUMBER using the format specified in string2. */
  @LibraryOperator(libraries = {TERADATA, POSTGRESQL, ORACLE})
  public static final SqlFunction TO_NUMBER =
      new SqlFunction(
        "TO_NUMBER",
        SqlKind.TO_NUMBER,
        ReturnTypes.BIGINT_FORCE_NULLABLE,
        null, OperandTypes.or(OperandTypes.STRING, OperandTypes.STRING_STRING,
        OperandTypes.family(SqlTypeFamily.STRING, SqlTypeFamily.NULL),
        OperandTypes.family(SqlTypeFamily.NULL, SqlTypeFamily.STRING),
        OperandTypes.STRING_STRING_STRING,
        OperandTypes.family(SqlTypeFamily.NULL)),
        SqlFunctionCategory.STRING);

  @LibraryOperator(libraries = {HIVE, SPARK})
  public static final SqlFunction CONV =
          new SqlFunction(
                  "CONV",
                  SqlKind.OTHER_FUNCTION,
                  ReturnTypes.VARCHAR_4_NULLABLE, null,
                  OperandTypes.family(SqlTypeFamily.STRING, SqlTypeFamily.NUMERIC,
                          SqlTypeFamily.NUMERIC),
                  SqlFunctionCategory.STRING);

  @LibraryOperator(libraries = {BIG_QUERY, HIVE, SPARK})
  public static final SqlFunction RPAD =
      new SqlFunction("RPAD", SqlKind.OTHER_FUNCTION,
        ReturnTypes.VARCHAR_2000_NULLABLE, null,
        OperandTypes.STRING_INTEGER_OPTIONAL_STRING,
        SqlFunctionCategory.STRING);

  @LibraryOperator(libraries = {BIG_QUERY, HIVE, SPARK})
  public static final SqlFunction LPAD =
      new SqlFunction("LPAD", SqlKind.OTHER_FUNCTION,
        ReturnTypes.VARCHAR_2000_NULLABLE, null,
        OperandTypes.STRING_INTEGER_OPTIONAL_STRING,
        SqlFunctionCategory.STRING);

  @LibraryOperator(libraries = {STANDARD})
  public static final SqlFunction STR_TO_DATE = new SqlFunction(
      "STR_TO_DATE",
      SqlKind.OTHER_FUNCTION,
      ReturnTypes.DATE_NULLABLE,
      null,
      OperandTypes.STRING_STRING,
      SqlFunctionCategory.TIMEDATE);

  @LibraryOperator(libraries = {BIG_QUERY})
  public static final SqlFunction PARSE_DATE =
      new SqlFunction(
        "PARSE_DATE",
        SqlKind.OTHER_FUNCTION,
        ReturnTypes.DATE_NULLABLE, null,
        OperandTypes.STRING_STRING,
        SqlFunctionCategory.TIMEDATE);

  @LibraryOperator(libraries = {BIG_QUERY})
  public static final SqlFunction PARSE_TIME =
      new SqlFunction(
          "PARSE_TIME",
          SqlKind.OTHER_FUNCTION,
          ReturnTypes.TIME_NULLABLE, null,
          OperandTypes.STRING_STRING,
          SqlFunctionCategory.TIMEDATE);

  @LibraryOperator(libraries = {BIG_QUERY})
  public static final SqlFunction PARSE_TIMESTAMP =
      new SqlFunction("PARSE_TIMESTAMP",
        SqlKind.OTHER_FUNCTION,
        ReturnTypes.TIMESTAMP_NULLABLE,
        null,
        OperandTypes.or(OperandTypes.STRING, OperandTypes.STRING_STRING),
        SqlFunctionCategory.TIMEDATE);

  @LibraryOperator(libraries = {BIG_QUERY})
  public static final SqlFunction PARSE_DATETIME =
      new SqlFunction("PARSE_DATETIME",
          SqlKind.OTHER_FUNCTION,
          ReturnTypes.ARG1_NULLABLE,
          null,
          OperandTypes.or(OperandTypes.STRING, OperandTypes.STRING_STRING),
          SqlFunctionCategory.TIMEDATE);

  @LibraryOperator(libraries = {HIVE, SPARK})
  public static final SqlFunction UNIX_TIMESTAMP =
      new SqlFunction(
        "UNIX_TIMESTAMP",
        SqlKind.OTHER_FUNCTION,
        ReturnTypes.BIGINT_NULLABLE, null,
        OperandTypes.family(ImmutableList.of(SqlTypeFamily.STRING, SqlTypeFamily.STRING),
          // both the operands are optional
          number -> number == 0 || number == 1),
        SqlFunctionCategory.TIMEDATE);

  @LibraryOperator(libraries = {HIVE, SPARK})
  public static final SqlFunction FROM_UNIXTIME =
      new SqlFunction(
        "FROM_UNIXTIME",
        SqlKind.OTHER_FUNCTION,
        ReturnTypes.VARCHAR_2000_NULLABLE, null,
        OperandTypes.family(ImmutableList.of(SqlTypeFamily.INTEGER, SqlTypeFamily.STRING),
          // Second operand is optional
          number -> number == 1),
        SqlFunctionCategory.TIMEDATE);

  @LibraryOperator(libraries = {STANDARD})
  public static final SqlFunction STRING_SPLIT = new SqlFunction(
      "STRING_SPLIT",
      SqlKind.OTHER_FUNCTION,
      ReturnTypes.MULTISET_NULLABLE,
      null,
      OperandTypes.STRING_STRING,
      SqlFunctionCategory.STRING);

  @LibraryOperator(libraries = {HIVE, SPARK})
  public static final SqlFunction SPLIT = new SqlFunction(
      "SPLIT",
      SqlKind.OTHER_FUNCTION,
      ReturnTypes.MULTISET_NULLABLE,
      null,
      OperandTypes.STRING_STRING,
      SqlFunctionCategory.STRING);

  /** The "TO_VARCHAR(numeric, string)" function; casts string
   * Format first_operand to specified in second operand. */
  @LibraryOperator(libraries = {SNOWFLAKE})
  public static final SqlFunction TO_VARCHAR =
      new SqlFunction(
        "TO_VARCHAR",
        SqlKind.OTHER_FUNCTION,
        ReturnTypes.VARCHAR_2000_NULLABLE, null,
        OperandTypes.family(SqlTypeFamily.NUMERIC, SqlTypeFamily.STRING),
        SqlFunctionCategory.STRING);

  @LibraryOperator(libraries = {BIG_QUERY})
  public static final SqlFunction TIMESTAMP_TO_DATE = new SqlFunction(
      "DATE",
      SqlKind.OTHER_FUNCTION,
      ReturnTypes.ARG0_NULLABLE,
      null,
      OperandTypes.DATETIME,
      SqlFunctionCategory.TIMEDATE);

  @LibraryOperator(libraries = {BIG_QUERY, SPARK})
  public static final SqlFunction FORMAT_DATETIME = new SqlFunction(
      "FORMAT_DATETIME",
      SqlKind.OTHER_FUNCTION,
      ReturnTypes.ARG0,
      null,
      OperandTypes.ANY_ANY,
      SqlFunctionCategory.TIMEDATE);

  /** Returns the index of search string in source string
   *  0 is returned when no match is found. */
  @LibraryOperator(libraries = {SNOWFLAKE, BIG_QUERY})
  public static final SqlFunction INSTR = new SqlFunction(
          "INSTR",
          SqlKind.OTHER_FUNCTION,
          ReturnTypes.INTEGER_NULLABLE,
          null,
          OperandTypes.family(ImmutableList.of
          (SqlTypeFamily.STRING, SqlTypeFamily.STRING,
          SqlTypeFamily.INTEGER, SqlTypeFamily.INTEGER),
              number -> number == 2 || number == 3),
          SqlFunctionCategory.STRING);

  @LibraryOperator(libraries = {MSSQL})
  public static final SqlFunction CHARINDEX = new SqlFunction(
          "CHARINDEX",
          SqlKind.OTHER_FUNCTION,
          ReturnTypes.INTEGER_NULLABLE,
          null,
          OperandTypes.family(ImmutableList.of
          (SqlTypeFamily.STRING, SqlTypeFamily.STRING,
          SqlTypeFamily.INTEGER),
              number -> number == 2),
          SqlFunctionCategory.STRING);

  @LibraryOperator(libraries = {BIG_QUERY})
  public static final SqlFunction TIME_DIFF = new SqlFunction(
          "TIME_DIFF",
          SqlKind.OTHER_FUNCTION,
          ReturnTypes.INTEGER,
          null,
          OperandTypes.family(SqlTypeFamily.DATETIME, SqlTypeFamily.DATETIME),
          SqlFunctionCategory.TIMEDATE);

  @LibraryOperator(libraries = {BIG_QUERY})
  public static final SqlFunction TIMESTAMPINTADD = new SqlFunction("TIMESTAMPINTADD",
          SqlKind.OTHER_FUNCTION,
          ReturnTypes.TIMESTAMP, null,
          OperandTypes.family(SqlTypeFamily.DATETIME, SqlTypeFamily.INTEGER),
          SqlFunctionCategory.TIMEDATE);

  @LibraryOperator(libraries = {BIG_QUERY})
  public static final SqlFunction TIMESTAMPINTSUB = new SqlFunction("TIMESTAMPINTSUB",
          SqlKind.OTHER_FUNCTION,
          ReturnTypes.TIMESTAMP, null,
          OperandTypes.family(SqlTypeFamily.DATETIME, SqlTypeFamily.INTEGER),
          SqlFunctionCategory.TIMEDATE);

  @LibraryOperator(libraries = {TERADATA})
  public static final SqlFunction WEEKNUMBER_OF_YEAR =
      new SqlFunction("WEEKNUMBER_OF_YEAR", SqlKind.OTHER_FUNCTION,
          ReturnTypes.INTEGER, null, OperandTypes.DATETIME,
          SqlFunctionCategory.TIMEDATE);

  @LibraryOperator(libraries = {TERADATA})
  public static final SqlFunction YEARNUMBER_OF_CALENDAR =
      new SqlFunction("YEARNUMBER_OF_CALENDAR", SqlKind.OTHER_FUNCTION,
          ReturnTypes.INTEGER, null, OperandTypes.DATETIME,
          SqlFunctionCategory.TIMEDATE);

  @LibraryOperator(libraries = {TERADATA})
  public static final SqlFunction MONTHNUMBER_OF_YEAR =
      new SqlFunction("MONTHNUMBER_OF_YEAR", SqlKind.OTHER_FUNCTION,
          ReturnTypes.INTEGER, null, OperandTypes.DATETIME,
          SqlFunctionCategory.TIMEDATE);

  @LibraryOperator(libraries = {TERADATA})
  public static final SqlFunction QUARTERNUMBER_OF_YEAR =
      new SqlFunction("QUARTERNUMBER_OF_YEAR", SqlKind.OTHER_FUNCTION,
          ReturnTypes.INTEGER, null, OperandTypes.DATETIME,
          SqlFunctionCategory.TIMEDATE);

  @LibraryOperator(libraries = {TERADATA})
  public static final SqlFunction WEEKNUMBER_OF_MONTH =
      new SqlFunction("WEEKNUMBER_OF_MONTH", SqlKind.OTHER_FUNCTION,
          ReturnTypes.INTEGER, null, OperandTypes.DATETIME,
          SqlFunctionCategory.TIMEDATE);

  @LibraryOperator(libraries = {TERADATA})
  public static final SqlFunction MONTHNUMBER_OF_QUARTER =
      new SqlFunction("MONTHNUMBER_OF_QUARTER", SqlKind.OTHER_FUNCTION,
          ReturnTypes.INTEGER, null, OperandTypes.DATETIME,
          SqlFunctionCategory.TIMEDATE);

  @LibraryOperator(libraries = {TERADATA})
  public static final SqlFunction WEEKNUMBER_OF_CALENDAR =
      new SqlFunction("WEEKNUMBER_OF_CALENDAR", SqlKind.OTHER_FUNCTION,
          ReturnTypes.INTEGER, null, OperandTypes.DATETIME,
          SqlFunctionCategory.TIMEDATE);

  @LibraryOperator(libraries = {TERADATA})
  public static final SqlFunction DAYOCCURRENCE_OF_MONTH =
      new SqlFunction("DAYOCCURRENCE_OF_MONTH", SqlKind.OTHER_FUNCTION,
          ReturnTypes.INTEGER, null, OperandTypes.DATETIME,
          SqlFunctionCategory.TIMEDATE);

  @LibraryOperator(libraries = {TERADATA})
  public static final SqlFunction DAYNUMBER_OF_CALENDAR =
        new SqlFunction("DAYNUMBER_OF_CALENDAR", SqlKind.OTHER_FUNCTION,
          ReturnTypes.INTEGER, null, OperandTypes.DATETIME,
          SqlFunctionCategory.TIMEDATE);

  @LibraryOperator(libraries = {BIG_QUERY})
  public static final SqlFunction DATE_DIFF =
      new SqlFunction("DATE_DIFF", SqlKind.OTHER_FUNCTION,
          ReturnTypes.INTEGER, null,
          OperandTypes.family(
              ImmutableList.of(SqlTypeFamily.DATE, SqlTypeFamily.DATE,
            SqlTypeFamily.STRING),
            number -> number == 2),
          SqlFunctionCategory.TIMEDATE);

  @LibraryOperator(libraries = {SPARK})
  public static final SqlFunction DATEDIFF =
      new SqlFunction("DATEDIFF", SqlKind.OTHER_FUNCTION,
          ReturnTypes.INTEGER, null,
          OperandTypes.family(SqlTypeFamily.DATE, SqlTypeFamily.DATE),
          SqlFunctionCategory.TIMEDATE);

  @LibraryOperator(libraries = {STANDARD})
  public static final SqlFunction DATE_MOD = new SqlFunction(
      "DATE_MOD",
      SqlKind.OTHER_FUNCTION,
      ReturnTypes.INTEGER_NULLABLE,
      null,
      OperandTypes.family(SqlTypeFamily.DATE, SqlTypeFamily.INTEGER),
      SqlFunctionCategory.STRING);

  @LibraryOperator(libraries = {TERADATA, SNOWFLAKE})
  public static final SqlFunction STRTOK = new SqlFunction(
      "STRTOK",
      SqlKind.OTHER_FUNCTION,
      ReturnTypes.VARCHAR_2000_NULLABLE,
      null,
      OperandTypes.or(OperandTypes.STRING_STRING_INTEGER,
          OperandTypes.family(SqlTypeFamily.NULL, SqlTypeFamily.STRING, SqlTypeFamily.INTEGER)),
      SqlFunctionCategory.STRING);

  @LibraryOperator(libraries = {BIG_QUERY})
  public static final SqlFunction TIME_SUB =
      new SqlFunction("TIME_SUB",
          SqlKind.MINUS,
          ReturnTypes.TIME,
          null,
          OperandTypes.DATETIME_INTERVAL,
          SqlFunctionCategory.TIMEDATE) {

        @Override public void unparse(SqlWriter writer, SqlCall call, int leftPrec, int rightPrec) {
          writer.getDialect().unparseIntervalOperandsBasedFunctions(
              writer, call, leftPrec, rightPrec);
        }
      };

  @LibraryOperator(libraries = {SNOWFLAKE})
  public static final SqlFunction TO_BINARY =
      new SqlFunction("TO_BINARY",
          SqlKind.OTHER_FUNCTION,
          ReturnTypes.BINARY,
          null,
          OperandTypes.family(
              ImmutableList.of(SqlTypeFamily.NUMERIC, SqlTypeFamily.STRING),
              number -> number == 1),
          SqlFunctionCategory.TIMEDATE);

  @LibraryOperator(libraries = {SNOWFLAKE})
  public static final SqlFunction TO_CHAR =
      new SqlFunction("TO_CHAR",
          SqlKind.OTHER_FUNCTION,
          ReturnTypes.VARCHAR_2000_NULLABLE, null,
              OperandTypes.family(SqlTypeFamily.NUMERIC, SqlTypeFamily.STRING),
          SqlFunctionCategory.STRING);

  @LibraryOperator(libraries = {NETEZZA})
  public static final SqlFunction MONTHS_BETWEEN =
      new SqlFunction("MONTHS_BETWEEN",
          SqlKind.OTHER_FUNCTION,
          ReturnTypes.INTEGER_NULLABLE, null,
          OperandTypes.family(SqlTypeFamily.DATE, SqlTypeFamily.DATE),
          SqlFunctionCategory.NUMERIC);


  @LibraryOperator(libraries = {BIG_QUERY})
  public static final SqlFunction REGEXP_MATCH_COUNT =
      new SqlFunction("REGEXP_MATCH_COUNT",
          SqlKind.OTHER_FUNCTION,
          ReturnTypes.VARCHAR_2000,
          null,
          OperandTypes.family(
              ImmutableList.of(SqlTypeFamily.STRING, SqlTypeFamily.STRING,
              SqlTypeFamily.NUMERIC, SqlTypeFamily.STRING),
              number -> number == 2 || number == 3),
          SqlFunctionCategory.NUMERIC);

  @LibraryOperator(libraries = {NETEZZA})
  public static final SqlFunction BITWISE_AND =
      new SqlFunction("BITWISE_AND",
          SqlKind.OTHER_FUNCTION,
          ReturnTypes.INTEGER_NULLABLE, null,
          OperandTypes.family(SqlTypeFamily.NUMERIC, SqlTypeFamily.NUMERIC),
          SqlFunctionCategory.NUMERIC);

  @LibraryOperator(libraries = {NETEZZA})
  public static final SqlFunction BITWISE_OR =
      new SqlFunction("BITWISE_OR",
          SqlKind.OTHER_FUNCTION,
          ReturnTypes.INTEGER_NULLABLE, null,
          OperandTypes.family(SqlTypeFamily.NUMERIC, SqlTypeFamily.NUMERIC),
          SqlFunctionCategory.NUMERIC);

  @LibraryOperator(libraries = {NETEZZA})
  public static final SqlFunction BITWISE_XOR =
      new SqlFunction("BITWISE_XOR",
          SqlKind.OTHER_FUNCTION,
          ReturnTypes.INTEGER_NULLABLE, null,
          OperandTypes.family(SqlTypeFamily.NUMERIC, SqlTypeFamily.NUMERIC),
          SqlFunctionCategory.NUMERIC);

  @LibraryOperator(libraries = {NETEZZA})
  public static final SqlFunction INT2SHL =
      new SqlFunction("INT2SHL",
          SqlKind.OTHER_FUNCTION,
          ReturnTypes.INTEGER_NULLABLE, null,
          OperandTypes.family(SqlTypeFamily.NUMERIC, SqlTypeFamily.NUMERIC,
                  SqlTypeFamily.NUMERIC),
          SqlFunctionCategory.NUMERIC);

  @LibraryOperator(libraries = {NETEZZA})
  public static final SqlFunction INT8XOR =
          new SqlFunction("INT8XOR",
                  SqlKind.OTHER_FUNCTION,
                  ReturnTypes.INTEGER_NULLABLE, null,
                  OperandTypes.family(SqlTypeFamily.NUMERIC, SqlTypeFamily.NUMERIC),
                  SqlFunctionCategory.NUMERIC);

  @LibraryOperator(libraries = {NETEZZA})
  public static final SqlFunction INT2SHR =
      new SqlFunction("INT2SHR",
          SqlKind.OTHER_FUNCTION,
          ReturnTypes.INTEGER_NULLABLE, null,
          OperandTypes.family(SqlTypeFamily.NUMERIC, SqlTypeFamily.NUMERIC,
                  SqlTypeFamily.NUMERIC),
          SqlFunctionCategory.NUMERIC);

  @LibraryOperator(libraries = {NETEZZA})
  public static final SqlFunction PI = new SqlFunction("PI",
          SqlKind.OTHER_FUNCTION,
          ReturnTypes.DECIMAL_MOD_NULLABLE, null,
          OperandTypes.family(SqlTypeFamily.NULL),
          SqlFunctionCategory.NUMERIC);

  @LibraryOperator(libraries = {NETEZZA})
  public static final SqlFunction ACOS = new SqlFunction("ACOS",
          SqlKind.OTHER_FUNCTION,
          ReturnTypes.DECIMAL_MOD_NULLABLE, null,
          OperandTypes.family(SqlTypeFamily.NUMERIC),
          SqlFunctionCategory.NUMERIC);

  @LibraryOperator(libraries = {NETEZZA})
  public static final SqlFunction OCTET_LENGTH = new SqlFunction("OCTET_LENGTH",
          SqlKind.OTHER_FUNCTION,
          ReturnTypes.INTEGER_NULLABLE, null,
          OperandTypes.family(SqlTypeFamily.CHARACTER),
          SqlFunctionCategory.NUMERIC);

  @LibraryOperator(libraries = {BIG_QUERY, SPARK})
  public static final SqlFunction REGEXP_CONTAINS =
      new SqlFunction("REGEXP_CONTAINS",
          SqlKind.OTHER_FUNCTION,
          ReturnTypes.BOOLEAN,
          null,
          OperandTypes.STRING_STRING,
          SqlFunctionCategory.NUMERIC);

  @LibraryOperator(libraries = {TERADATA})
  public static final SqlFunction HASHBUCKET =
      new SqlFunction(
          "HASHBUCKET",
          SqlKind.OTHER_FUNCTION,
          ReturnTypes.INTEGER_NULLABLE,
          null,
          OperandTypes.INTEGER,
          SqlFunctionCategory.SYSTEM);

  @LibraryOperator(libraries = {TERADATA})
  public static final SqlFunction HASHROW =
      new SqlFunction(
          "HASHROW",
          SqlKind.OTHER_FUNCTION,
          ReturnTypes.INTEGER_NULLABLE,
          null,
          OperandTypes.ONE_OR_MORE,
          SqlFunctionCategory.SYSTEM);

  @LibraryOperator(libraries = {BIG_QUERY})
  public static final SqlFunction FARM_FINGERPRINT =
      new SqlFunction(
          "FARM_FINGERPRINT",
          SqlKind.OTHER_FUNCTION,
          ReturnTypes.INTEGER_NULLABLE,
          null,
          OperandTypes.STRING,
          SqlFunctionCategory.SYSTEM);

  @LibraryOperator(libraries = {NETEZZA})
  public static final SqlFunction ROWID =
      new SqlFunction(
          "ROWID",
          SqlKind.OTHER_FUNCTION,
          ReturnTypes.INTEGER_NULLABLE,
          null,
          null,
          SqlFunctionCategory.SYSTEM);

  @LibraryOperator(libraries = {TERADATA})
  public static final SqlFunction TRUNC =
      new SqlFunction(
          "TRUNC",
          SqlKind.OTHER_FUNCTION,
          ReturnTypes.DATE,
          null,
          OperandTypes.family(SqlTypeFamily.DATE,
          SqlTypeFamily.STRING), SqlFunctionCategory.SYSTEM);

  @LibraryOperator(libraries = {SPARK, BIG_QUERY})
  public static final SqlFunction DATE_TRUNC =
      new SqlFunction(
          "DATE_TRUNC",
          SqlKind.OTHER_FUNCTION,
          ReturnTypes.TIMESTAMP,
          null,
          OperandTypes.family(SqlTypeFamily.STRING,
              SqlTypeFamily.TIMESTAMP), SqlFunctionCategory.SYSTEM);

  @LibraryOperator(libraries = {SPARK})
  public static final SqlFunction RAISE_ERROR =
      new SqlFunction("RAISE_ERROR",
          SqlKind.OTHER_FUNCTION,
          null,
          null,
          OperandTypes.STRING,
          SqlFunctionCategory.SYSTEM);

  @LibraryOperator(libraries = {NETEZZA})
  public static final SqlFunction TRUE =
      new SqlFunction(
          "TRUE",
          SqlKind.OTHER_FUNCTION,
          ReturnTypes.BOOLEAN,
          null,
          null,
          SqlFunctionCategory.SYSTEM);

  @LibraryOperator(libraries = {NETEZZA})
  public static final SqlFunction FALSE =
      new SqlFunction(
          "FALSE",
          SqlKind.OTHER_FUNCTION,
          ReturnTypes.BOOLEAN,
          null,
          null,
          SqlFunctionCategory.SYSTEM);

  @LibraryOperator(libraries = {BIG_QUERY})
  public static final SqlFunction PARENTHESIS =
      new SqlFunction(
          "PARENTHESIS",
          SqlKind.OTHER_FUNCTION,
          ReturnTypes.COLUMN_LIST,
          null,
          OperandTypes.ANY,
          SqlFunctionCategory.SYSTEM) {
        @Override public void unparse(SqlWriter writer, SqlCall call, int leftPrec, int rightPrec) {
          final SqlWriter.Frame parenthesisFrame = writer.startList("(", ")");
          for (SqlNode operand : call.getOperandList()) {
            writer.sep(",");
            operand.unparse(writer, leftPrec, rightPrec);
          }
          writer.endList(parenthesisFrame);
        }
      };
}
