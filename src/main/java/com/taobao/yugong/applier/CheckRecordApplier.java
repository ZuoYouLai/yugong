package com.taobao.yugong.applier;

import com.google.common.base.Preconditions;
import com.google.common.collect.Lists;
import com.google.common.collect.MigrateMap;
import com.taobao.yugong.common.db.RecordDiffer;
import com.taobao.yugong.common.db.meta.ColumnMeta;
import com.taobao.yugong.common.db.meta.ColumnValue;
import com.taobao.yugong.common.db.meta.Table;
import com.taobao.yugong.common.db.meta.TableMetaGenerator;
import com.taobao.yugong.common.db.sql.SqlTemplates;
import com.taobao.yugong.common.model.DbType;
import com.taobao.yugong.common.model.YuGongContext;
import com.taobao.yugong.common.model.record.Record;
import com.taobao.yugong.common.utils.YuGongUtils;
import com.taobao.yugong.exception.YuGongException;

import org.apache.commons.lang.ObjectUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.PreparedStatementCallback;

import java.sql.ResultSet;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;


/**
 * 增加数据对比
 *
 * @author agapple 2013-9-29 下午1:14:03
 */
public class CheckRecordApplier extends AbstractRecordApplier {

  protected static final Logger logger = LoggerFactory.getLogger(CheckRecordApplier.class);
  protected Map<List<String>, TableSqlUnit> selectSqlCache;
  protected Map<List<String>, Table> tableCache;
  protected YuGongContext context;
  protected DbType dbType;

  public CheckRecordApplier(YuGongContext context) {
    this.context = context;
  }

  public void start() {
    super.start();

    dbType = YuGongUtils.judgeDbType(context.getTargetDs());
    tableCache = MigrateMap.makeComputingMap(names -> {
      if (names.size() != 2) {
        throw new YuGongException("names[" + names.toString() + "] is not valid");
      }

      return TableMetaGenerator.getTableMeta(dbType, context.getTargetDs(),
          context.isIgnoreSchema() ? null : names.get(0),
          names.get(1));
    });

    selectSqlCache = MigrateMap.makeMap();
  }

  public void stop() {
    super.stop();
  }

  public void apply(List<Record> records) throws YuGongException {
    // no one,just return
    if (YuGongUtils.isEmpty(records)) {
      return;
    }

    doApply(records);
  }

  protected void doApply(List<Record> records) {
    Map<List<String>, List<Record>> buckets = MigrateMap.makeComputingMap(names -> Lists.newArrayList());

    // 根据目标库的不同，划分为多个bucket
    for (Record record : records) {
      buckets.get(Arrays.asList(record.getSchemaName(), record.getTableName())).add(record);
    }

    JdbcTemplate jdbcTemplate = new JdbcTemplate(context.getTargetDs());
    for (final List<Record> batchRecords : buckets.values()) {
      List<Record> queryRecords;
      if (context.isBatchApply()) {
        queryRecords = queryByBatch(jdbcTemplate, batchRecords);
      } else {
        queryRecords = queryOneByOne(jdbcTemplate, batchRecords);
      }

      diff(batchRecords, queryRecords);
    }
  }

  protected List<Record> queryByBatch(JdbcTemplate jdbcTemplate, final List<Record> batchRecords) {
    Preconditions.checkArgument(batchRecords.size() > 0, "do not exists data");

    TableSqlUnit sqlUnit = getSqlUnit(batchRecords.get(0));
    final String schemaName = batchRecords.get(0).getSchemaName();
    final String tableName = batchRecords.get(0).getTableName();
    final Map<String, Integer> indexs = sqlUnit.applierIndexs;
    final List<ColumnMeta> primaryKeys = getPrimaryMetas(batchRecords.get(0));
    final List<ColumnMeta> columns = getColumnMetas(batchRecords.get(0));
    Table meta = TableMetaGenerator.getTableMeta(dbType, context.getTargetDs(),
        context.isIgnoreSchema() ? null : batchRecords.get(0).getSchemaName(),
        batchRecords.get(0).getTableName());

    String selectSql = null;
    if (dbType == DbType.MYSQL) {
      selectSql = SqlTemplates.MYSQL.getSelectInSql(meta.getSchema(),
          meta.getName(),
          YuGongUtils.getColumnNameArray(primaryKeys),
          YuGongUtils.getColumnNameArray(columns),
          batchRecords.size());
    } else if (dbType == DbType.ORACLE) {
      selectSql = SqlTemplates.ORACLE.getSelectInSql(meta.getSchema(),
          meta.getName(),
          YuGongUtils.getColumnNameArray(primaryKeys),
          YuGongUtils.getColumnNameArray(columns),
          batchRecords.size());
    }

    Object results = jdbcTemplate.execute(selectSql, (PreparedStatementCallback) ps -> {
      // 批量查询，根据pks in 语法
      int size = batchRecords.get(0).getPrimaryKeys().size();
      int i = 0;
      for (Record record : batchRecords) {
        int count = 0;
        for (ColumnValue pk : record.getPrimaryKeys()) {
          // 源库和目标的库主键信息可能不一致
          Integer index = getIndex(indexs, pk, true);
          if (index != null) {
            ps.setObject(size * i + index, pk.getValue(), pk.getColumn().getType());
            count++;
          }
        }

        for (ColumnValue col : record.getColumns()) {
          // 源库和目标的库主键信息可能不一致
          Integer index = getIndex(indexs, col, true);
          if (index != null) {
            ps.setObject(size * i + index, col.getValue(), col.getColumn().getType());
            count++;
          }
        }

        if (count != indexs.size()) {
          processMissColumn(record, indexs);
        }

        i++;
      }

      List<Record> result = Lists.newArrayList();
      ResultSet rs = ps.executeQuery();
      while (rs.next()) {
        List<ColumnValue> cms = new ArrayList<>();
        List<ColumnValue> pks = new ArrayList<>();
        // 需要和源库转义后的record保持相同的primary/column顺序，否则对比会失败
        for (ColumnMeta pk : primaryKeys) {
          ColumnValue cv = YuGongUtils.getColumnValue(rs, getTargetEncoding(), pk);
          pks.add(cv);
        }

        for (ColumnMeta col : columns) {
          ColumnValue cv = YuGongUtils.getColumnValue(rs, getTargetEncoding(), col);
          cms.add(cv);
        }

        Record re = new Record(schemaName, tableName, pks, cms);
        result.add(re);
      }

      return result;
    });
    return (List<Record>) results;
  }

  /**
   * 一条条记录串行处理
   */
  protected List<Record> queryOneByOne(JdbcTemplate jdbcTemplate, final List<Record> records) {
    TableSqlUnit sqlUnit = getSqlUnit(records.get(0));
    String selectSql = sqlUnit.applierSql;
    final Map<String, Integer> indexs = sqlUnit.applierIndexs;
    final List<ColumnMeta> primaryKeys = getPrimaryMetas(records.get(0));
    final List<ColumnMeta> columns = getColumnMetas(records.get(0));
    Object results = jdbcTemplate.execute(selectSql, (PreparedStatementCallback) ps -> {
      List<Record> result = Lists.newArrayList();
      for (Record record : records) {

        int count = 0;
        for (ColumnValue pk : record.getPrimaryKeys()) {
          // 源库和目标的库主键信息可能不一致
          Integer index = getIndex(indexs, pk, true);
          if (index != null) {
            ps.setObject(index, pk.getValue(), pk.getColumn().getType());
            count++;
          }
        }

        for (ColumnValue col : record.getColumns()) {
          // 源库和目标的库主键信息可能不一致
          Integer index = getIndex(indexs, col, true);
          if (index != null) {
            ps.setObject(index, col.getValue(), col.getColumn().getType());
            count++;
          }
        }

        if (count != indexs.size()) {
          processMissColumn(record, indexs);
        }

        ResultSet rs = ps.executeQuery();
        while (rs.next()) {
          List<ColumnValue> cms = new ArrayList<>();
          List<ColumnValue> pks = new ArrayList<>();
          // 需要和源库转义后的record保持相同的primary/column顺序，否则对比会失败
          for (ColumnMeta pk : primaryKeys) {
            ColumnValue cv = YuGongUtils.getColumnValue(rs, getTargetEncoding(), pk);
            pks.add(cv);
          }

          for (ColumnMeta col : columns) {
            ColumnValue cv = YuGongUtils.getColumnValue(rs, getTargetEncoding(), col);
            cms.add(cv);
          }

          Record re = new Record(record.getSchemaName(), record.getTableName(), pks, cms);
          result.add(re);
        }
      }
      return result;
    });
    return (List<Record>) results;
  }

  protected String getTargetEncoding() {
    if (dbType.isOracle()) {
      return context.getTargetEncoding();
    } else {
      return null;
    }
  }


  /**
   * @param records1 源库的数据
   * @param records2 目标库的数据
   */
  protected void diff(List<Record> records1, List<Record> records2) {

    Map<List<String>, Record> recordMap2 = new HashMap<>();
    for (Record record : records2) {
      List<String> objs = Lists.newArrayList();
      for (ColumnValue pk : record.getPrimaryKeys()) {
        objs.add(ObjectUtils.toString(pk.getValue()));
      }

      recordMap2.put(objs, record);
    }

    // 以records1为准
    for (Record record : records1) {
      List<String> objs = Lists.newArrayList();

      for (ColumnValue pk : record.getPrimaryKeys()) {
        objs.add(ObjectUtils.toString(pk.getValue()));
      }

      RecordDiffer.diff(record, recordMap2.remove(objs));
    }

    // 比对record2多余的数据
    for (Record record2 : recordMap2.values()) {
      RecordDiffer.diff(null, record2);
    }
  }

  protected TableSqlUnit getSqlUnit(Record record) {
    List<String> names = Arrays.asList(record.getSchemaName(), record.getTableName());
    TableSqlUnit sqlUnit = selectSqlCache.get(names);
    if (sqlUnit != null) {
      return sqlUnit;
    }
    synchronized (names) {
      sqlUnit = selectSqlCache.get(names);
      if (sqlUnit == null) { // double-check
        sqlUnit = new TableSqlUnit();
        String applierSql = null;
        Table meta = TableMetaGenerator.getTableMeta(dbType, context.getTargetDs(),
            context.isIgnoreSchema() ? null : names.get(0),
            names.get(1));

        String[] primaryKeys = getPrimaryNames(record);
        String[] columns = getColumnNames(record);

        if (dbType == DbType.MYSQL) {
          applierSql = SqlTemplates.MYSQL.getSelectSql(meta.getSchema(),
              meta.getName(),
              primaryKeys,
              columns);
        } else if (dbType == DbType.ORACLE) {
          applierSql = SqlTemplates.ORACLE.getSelectSql(meta.getSchema(),
              meta.getName(),
              primaryKeys,
              columns);
        } else {
          // FIXME throw exception
        }

        int index = 1;
        Map<String, Integer> indexs = new HashMap<>();
        for (String column : primaryKeys) {
          indexs.put(column, index);
          index++;
        }

        if (index == 1) { // 没有主键
          for (String column : columns) {
            indexs.put(column, index);
            index++;
          }
        }

        // 检查下是否少了列
        checkColumns(meta, indexs); // TODO add translator

        sqlUnit.applierSql = applierSql;
        sqlUnit.applierIndexs = indexs;
        selectSqlCache.put(names, sqlUnit);
      }
    }

    return sqlUnit;
  }

  protected void processMissColumn(final Record record, final Map<String, Integer> indexs) {
    // 如果数量不同，则认为缺少主键
    List<String> allNames = new ArrayList<>(indexs.keySet());
    for (ColumnValue cv : record.getColumns()) {
      Integer index = getIndex(indexs, cv, true);
      if (index != null) {
        allNames.remove(cv.getColumn().getName());
      }
    }

    for (ColumnValue pk : record.getPrimaryKeys()) {
      Integer index = getIndex(indexs, pk, true);
      if (index != null) {
        allNames.remove(pk.getColumn().getName());
      }
    }

    throw new YuGongException("miss columns" + allNames + " and failed Record Data : " + record.toString());
  }

}
