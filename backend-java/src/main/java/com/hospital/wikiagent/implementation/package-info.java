/**
 * 医院新增指标从草稿、字段映射、SQL 试运行到审批发布的流程。
 *
 * <p>指标草稿、审批和发布形成版本化闭环，未审批规则不能成为生效口径；发布过程保留审计身份与差异。</p>
 */
package com.hospital.wikiagent.implementation;
