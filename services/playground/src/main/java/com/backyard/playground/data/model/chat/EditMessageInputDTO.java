package com.backyard.playground.data.model.chat;

/**
 * Input for editing a message body.
 * The row is located by (channel_id, message_id) — both supplied as path
 * variables by the REST controller. Only the new body is needed here.
 */
public class EditMessageInputDTO {

    private String body;

    public String getBody() {
        return body;
    }

    public void setBody(String body) {
        this.body = body;
    }
}
