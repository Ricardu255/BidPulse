package com.bidpulse.infra.persistence;

import com.bidpulse.audit.AuditLog;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Options;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;

/** 审计日志写入与查询；只由控制台/管理端低频调用，不在竞价热路径上。 */
@Mapper
public interface AuditLogMapper {

    @Insert("INSERT INTO audit_log(actor, role, method, path, status, ip, detail) "
            + "VALUES(#{actor}, #{role}, #{method}, #{path}, #{status}, #{ip}, #{detail})")
    @Options(useGeneratedKeys = true, keyProperty = "id")
    int insert(AuditLog log);

    @Select("SELECT id, actor, role, method, path, status, ip, detail, created_at "
            + "FROM audit_log ORDER BY id DESC LIMIT #{limit}")
    List<AuditLog> recent(@Param("limit") int limit);
}
