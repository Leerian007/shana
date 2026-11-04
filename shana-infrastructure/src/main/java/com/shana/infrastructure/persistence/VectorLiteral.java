package com.shana.infrastructure.persistence;

/**
 * 把 {@code float[]} 转成 pgvector 的文本字面量，形如 {@code [0.1,-0.2,0.3]}。
 *
 * <p>为什么不引入 pgvector 的 JDBC 扩展：那需要额外的驱动依赖，并且要把类型注册到
 * 全局类型处理器上，一旦与 MyBatis-Plus 的自动映射冲突，报错信息非常难懂。
 * 这里只有"写入"和"检索"两处需要传向量，字符串字面量 + {@code CAST} 更直接。
 *
 * <p>另一个好处是这里可以主动拦截非法数值：NaN / Infinity 交给数据库会报
 * "invalid input syntax for type vector"，而错误信息里看不出是哪条 chunk 出的问题。
 */
final class VectorLiteral {

    private VectorLiteral() {
    }

    static String format(float[] vector) {
        if (vector == null || vector.length == 0) {
            throw new IllegalArgumentException("向量不能为空");
        }
        StringBuilder builder = new StringBuilder(vector.length * 8 + 2).append('[');
        for (int i = 0; i < vector.length; i++) {
            float value = vector[i];
            if (Float.isNaN(value) || Float.isInfinite(value)) {
                throw new IllegalArgumentException("向量第 " + i + " 维不是有限数值: " + value);
            }
            if (i > 0) {
                builder.append(',');
            }
            builder.append(value);
        }
        return builder.append(']').toString();
    }
}
