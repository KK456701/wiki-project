/*
  由 DBA 在 SQLCMD 模式执行：
    sqlcmd -S <server> -E -v WriterPassword="<strong-random-password>" -i create-winex-aima-agent-writer.sql

  固定登录名、数据库、Schema 和对象集合，禁止把该脚本改造成动态数据库/表名入口。
*/
USE [master];
GO

IF SUSER_ID(N'winex_aima_agent_writer') IS NULL
BEGIN
    CREATE LOGIN [winex_aima_agent_writer]
      WITH PASSWORD = '$(WriterPassword)',
           DEFAULT_DATABASE = [winex_aima],
           CHECK_POLICY = ON,
           CHECK_EXPIRATION = ON;
END;
GO

IF IS_SRVROLEMEMBER(N'sysadmin', N'winex_aima_agent_writer') = 1
    THROW 51000, 'Dedicated writer must not be sysadmin.', 1;
GO

USE [winex_aima];
GO

IF USER_ID(N'winex_aima_agent_writer') IS NULL
    CREATE USER [winex_aima_agent_writer]
      FOR LOGIN [winex_aima_agent_writer];
GO

GRANT CONNECT TO [winex_aima_agent_writer];
DENY ALTER TO [winex_aima_agent_writer];
/*
  CONTROL 是数据库级父权限。DENY CONTROL 会连同 CONNECT 和对象级
  SELECT/INSERT/DELETE 一起被拒绝，不能用它表达“未授予 CONTROL”。
  REVOKE 同时清理旧版脚本留下的错误 DENY，而不会授予任何额外权限。
*/
REVOKE CONTROL TO [winex_aima_agent_writer];
DENY TAKE OWNERSHIP TO [winex_aima_agent_writer];
DENY CREATE TABLE TO [winex_aima_agent_writer];
DENY CREATE VIEW TO [winex_aima_agent_writer];
GO

GRANT SELECT, INSERT, DELETE ON OBJECT::[dbo].[BUSINESS_UNIT_X_BU_TYPE] TO [winex_aima_agent_writer];
GRANT SELECT, INSERT, DELETE ON OBJECT::[dbo].[CLIBASIC_SURGERY] TO [winex_aima_agent_writer];
GRANT SELECT, INSERT, DELETE ON OBJECT::[dbo].[INPATIENT_ENCOUNTER] TO [winex_aima_agent_writer];
GRANT SELECT, INSERT, DELETE ON OBJECT::[dbo].[INPAT_TRANSFER] TO [winex_aima_agent_writer];
GRANT SELECT, INSERT, DELETE ON OBJECT::[dbo].[INP_CLI_ORDER] TO [winex_aima_agent_writer];
GRANT SELECT, INSERT, DELETE ON OBJECT::[dbo].[INP_SURGICAL_ANESTHESIA_PLAN] TO [winex_aima_agent_writer];
GRANT SELECT, INSERT, DELETE ON OBJECT::[dbo].[MRAS_BUSINESS_ANTI] TO [winex_aima_agent_writer];
GRANT SELECT, INSERT, DELETE ON OBJECT::[dbo].[MRAS_BUSINESS_BLOOD_AUDIT] TO [winex_aima_agent_writer];
GRANT SELECT, INSERT, DELETE ON OBJECT::[dbo].[MRAS_BUSINESS_CONSULTATION] TO [winex_aima_agent_writer];
GRANT SELECT, INSERT, DELETE ON OBJECT::[dbo].[MRAS_BUSINESS_CRITICAL_RPT] TO [winex_aima_agent_writer];
GRANT SELECT, INSERT, DELETE ON OBJECT::[dbo].[MRAS_BUSINESS_DEATH] TO [winex_aima_agent_writer];
GRANT SELECT, INSERT, DELETE ON OBJECT::[dbo].[MRAS_BUSINESS_DIFFI_EMR] TO [winex_aima_agent_writer];
GRANT SELECT, INSERT, DELETE ON OBJECT::[dbo].[MRAS_BUSINESS_DIFFI_EMR_SECOND] TO [winex_aima_agent_writer];
GRANT SELECT, INSERT, DELETE ON OBJECT::[dbo].[MRAS_BUSINESS_FIRSTVISIT] TO [winex_aima_agent_writer];
GRANT SELECT, INSERT, DELETE ON OBJECT::[dbo].[MRAS_BUSINESS_GRADED_CARE] TO [winex_aima_agent_writer];
GRANT SELECT, INSERT, DELETE ON OBJECT::[dbo].[MRAS_BUSINESS_OP_DISC] TO [winex_aima_agent_writer];
GRANT SELECT, INSERT, DELETE ON OBJECT::[dbo].[MRAS_BUSINESS_PATRESCUE] TO [winex_aima_agent_writer];
GRANT SELECT, INSERT, DELETE ON OBJECT::[dbo].[MRAS_BUSINESS_SHIFTHANDOVER] TO [winex_aima_agent_writer];
GRANT SELECT, INSERT, DELETE ON OBJECT::[dbo].[MRAS_BUSINESS_SURGERY] TO [winex_aima_agent_writer];
GRANT SELECT, INSERT, DELETE ON OBJECT::[dbo].[MRAS_BUSINESS_SUR_GRADE] TO [winex_aima_agent_writer];
GRANT SELECT, INSERT, DELETE ON OBJECT::[dbo].[MRAS_BUSINESS_WARDROUND] TO [winex_aima_agent_writer];
GRANT SELECT, INSERT, DELETE ON OBJECT::[dbo].[MRAS_INDEX_SURGREC] TO [winex_aima_agent_writer];
GRANT SELECT, INSERT, DELETE ON OBJECT::[dbo].[MRAS_MEDTECH_PRO] TO [winex_aima_agent_writer];
GRANT SELECT, INSERT, DELETE ON OBJECT::[dbo].[MRAS_MEDTECH_PROC] TO [winex_aima_agent_writer];
GRANT SELECT, INSERT, DELETE ON OBJECT::[dbo].[MRAS_ORGANIZATION] TO [winex_aima_agent_writer];
GRANT SELECT, INSERT, DELETE ON OBJECT::[dbo].[MRAS_PATIENT_EVENT] TO [winex_aima_agent_writer];
GRANT SELECT, INSERT, DELETE ON OBJECT::[dbo].[MRAS_TARGET_DEFINITION] TO [winex_aima_agent_writer];
GRANT SELECT, INSERT, DELETE ON OBJECT::[dbo].[ORGANIZATION] TO [winex_aima_agent_writer];
GO

DENY UPDATE ON SCHEMA::[dbo] TO [winex_aima_agent_writer];
DENY ALTER ON SCHEMA::[dbo] TO [winex_aima_agent_writer];
GO

IF EXISTS (
    SELECT 1
    FROM sys.database_role_members drm
    JOIN sys.database_principals role_p ON role_p.principal_id = drm.role_principal_id
    JOIN sys.database_principals member_p ON member_p.principal_id = drm.member_principal_id
    WHERE member_p.name = N'winex_aima_agent_writer'
      AND role_p.name IN (N'db_owner', N'db_ddladmin', N'db_securityadmin')
)
    THROW 51001, 'Dedicated writer has a forbidden database role.', 1;
GO
