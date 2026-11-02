/**
 * 基础设施层：实现 core 定义的端口，以及数据库、缓存、外部 HTTP 等技术细节。
 *
 * <p>当前包含：
 * <ul>
 *   <li>MyBatis-Plus + PostgreSQL：数据访问能力（尚无表与 Mapper）；</li>
 *   <li>Redis：客户端已就绪，Spring Boot 自带的 {@code StringRedisTemplate} 可直接使用，暂未接入业务。</li>
 * </ul>
 *
 * <p>原则：本模块可以依赖 core，core 不允许反向依赖本模块。
 */
package com.shana.infrastructure;
