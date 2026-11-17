package com.liubai.diary.data;

import org.apache.ibatis.annotations.*;

@Mapper
public interface ImportBatchMapper {
    record BatchRow(long id,int importedCount,int skippedCount,String resultJson) {}
    @Insert("INSERT IGNORE INTO import_batches(owner_id,operation_id,imported_count,skipped_count,result_json) VALUES(#{ownerId},#{operationId},0,0,JSON_OBJECT('state','RUNNING'))")
    int start(@Param("ownerId") long ownerId,@Param("operationId") String operationId);
    @Select("SELECT id,imported_count AS importedCount,skipped_count AS skippedCount,CAST(result_json AS CHAR) AS resultJson FROM import_batches WHERE owner_id=#{ownerId} AND operation_id=#{operationId} FOR UPDATE")
    BatchRow findForUpdate(@Param("ownerId") long ownerId,@Param("operationId") String operationId);
    @Select("SELECT id,imported_count AS importedCount,skipped_count AS skippedCount,CAST(result_json AS CHAR) AS resultJson FROM import_batches WHERE owner_id=#{ownerId} AND operation_id=#{operationId}")
    BatchRow find(@Param("ownerId") long ownerId,@Param("operationId") String operationId);
    @Update("UPDATE import_batches SET imported_count=#{imported},skipped_count=#{skipped},result_json=JSON_OBJECT('state','DONE','conflicts',#{conflicts},'expired',#{expired}) WHERE id=#{id}")
    int finish(@Param("id") long id,@Param("imported") int imported,@Param("skipped") int skipped,
               @Param("conflicts") int conflicts,@Param("expired") int expired);
}
