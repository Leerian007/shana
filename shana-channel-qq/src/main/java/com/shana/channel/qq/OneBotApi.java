package com.shana.channel.qq;

import com.fasterxml.jackson.databind.JsonNode;

import java.util.concurrent.CompletableFuture;

/**
 * OneBot 动作调用端口（{@code send_group_msg}、{@code get_status} 等）。
 *
 * <p>抽成接口有两个原因：发送逻辑可以拿它做测试替身，不必真的连 NapCat；
 * 将来若要换成 HTTP 模式（NapCat 同时支持 HTTP POST），业务代码一行不用改。
 */
public interface OneBotApi {

    /**
     * 调用一个 OneBot 动作。
     *
     * @param action 动作名，例如 {@code send_group_msg}
     * @param params 参数对象，会被序列化成 JSON 的 {@code params} 字段
     * @return 完整响应；{@code retcode != 0} 时以异常完成
     */
    CompletableFuture<JsonNode> call(String action, Object params);
}
