/**
 * Java 单运行时的应用入口与全局配置。
 *
 * <p>根包只负责 Spring Boot 启动和全局组件扫描；领域实现必须进入明确子包，避免把跨模块依赖堆到启动类中。</p>
 */
package com.hospital.wikiagent;
