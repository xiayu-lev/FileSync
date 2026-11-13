package com.filesync.common.model;

/**
 * 跨端日志消息：同步的发起端把每条日志转发给对端，对端在自身日志区同步显示。
 */
public class PeerLogMessage {

    private String from;
    private String text;

    public PeerLogMessage() {
    }

    public PeerLogMessage(String from, String text) {
        this.from = from;
        this.text = text;
    }

    public String getFrom() {
        return from;
    }

    public void setFrom(String from) {
        this.from = from;
    }

    public String getText() {
        return text;
    }

    public void setText(String text) {
        this.text = text;
    }
}
