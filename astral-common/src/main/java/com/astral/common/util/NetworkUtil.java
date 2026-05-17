package com.astral.common.util;

import cn.hutool.core.net.NetUtil;

/**
 * 网络工具类
 *
 * 封装 Hutool 的网络操作能力，提供获取本机 IP 和 MAC 地址的便捷方法。
 * 主要用于集群模式下节点标识的生成，以及日志中记录请求来源信息。
 */
public class NetworkUtil {

    /**
     * 获取本机 IP 地址
     *
     * 底层使用 Hutool 的 NetUtil.getLocalhostStr()，自动处理多网卡环境下的
     * IP 选择逻辑，优先返回非回环地址。
     *
     * @return 本机 IPv4 地址字符串
     */
    public static String getLocalIp() {
        return NetUtil.getLocalhostStr();
    }

    /**
     * 获取本机 MAC 地址
     *
     * 用于集群模式下生成唯一的节点标识，确保不同物理机之间的区分度。
     * 注意：在容器化部署环境中 MAC 地址可能不可靠，需结合其他方式识别节点。
     *
     * @return 本机 MAC 地址字符串，格式如 "AA-BB-CC-DD-EE-FF"
     */
    public static String getLocalMac() {
        return NetUtil.getLocalMacAddress();
    }
}
