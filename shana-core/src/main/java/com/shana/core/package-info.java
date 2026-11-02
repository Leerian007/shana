/**
 * 核心业务模块：定义领域模型与业务接口，编排 model / memory / agent 的能力。
 *
 * <p>约定：
 * <ul>
 *   <li>只写业务规则与编排，不出现 SQL、HTTP 客户端、厂商 SDK；</li>
 *   <li>需要外部能力时，在本模块定义端口接口，由 infrastructure 实现。</li>
 * </ul>
 */
package com.shana.core;
