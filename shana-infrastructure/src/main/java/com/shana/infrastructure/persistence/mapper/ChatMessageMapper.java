package com.shana.infrastructure.persistence.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.shana.infrastructure.persistence.entity.ChatMessageEntity;

/**
 * chat_message 表的 Mapper。
 *
 * <p>当前只用 {@link BaseMapper} 提供的通用方法（insert / selectPage / delete），
 * 没有自定义 SQL。等出现复杂查询（比如按月聚合统计）时再写 XML 或注解 SQL。
 *
 * <p>注册方式：由 {@code MybatisPlusConfig} 上的 {@code @MapperScan} 统一扫描，
 * 因此本接口不需要 {@code @Mapper} 注解。
 */
public interface ChatMessageMapper extends BaseMapper<ChatMessageEntity> {
}
