/**
 * QQ 渠道：通过 OneBot v11 协议对接 NapCat，把群聊/私聊消息接入问答链路。
 *
 * <p>分层约定：
 * <ul>
 *   <li>协议细节（WebSocket、JSON、动作名）只出现在 {@link com.shana.channel.qq.OneBotWsClient}
 *       与 {@link com.shana.channel.qq.OneBotMessageSender}；</li>
 *   <li>消息形态由 {@link com.shana.channel.qq.OneBotEventParser} 翻译成
 *       {@link com.shana.channel.qq.IncomingMessage}，之后就不再出现 QQ 的概念；</li>
 *   <li>"回不回"由 {@link com.shana.channel.qq.ReplyPolicy} 决定，"怎么回"由
 *       {@link com.shana.channel.qq.QqChatHandler} 编排。</li>
 * </ul>
 *
 * <p>这样拆的直接收益是：换协议实现（OneBot 12 / Milky）只动前两层，
 * 业务层与 {@code ChatService} 完全不受影响。
 */
package com.shana.channel.qq;
