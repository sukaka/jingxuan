package com.whale.crm.integration.wecom.adapter;

public record WecomMediaChunk(byte[] data, String nextIndex, boolean finished) {
    public WecomMediaChunk {
        data = data == null ? new byte[0] : data.clone();
    }

    @Override
    public byte[] data() {
        return data.clone();
    }
}
