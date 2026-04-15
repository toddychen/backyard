package com.backyard.playground.data.model.chat;

import java.util.List;

import com.backyard.playground.data.model.UserDTO;

/** Response for the channel list endpoint, split by channel kind. */
public class ChannelListDTO {

    /** PUBLIC and PRIVATE channels the user has explicitly joined. */
    private List<ChannelDTO> channels;

    /** DM channels the user is a participant in. */
    private List<ChannelDTO> dms;

    /**
     * User profiles for all DM participants — lets the client render DM channel
     * names without a follow-up request.
     */
    private List<UserDTO> users;

    public ChannelListDTO(List<ChannelDTO> channels, List<ChannelDTO> dms, List<UserDTO> users) {
        this.channels = channels;
        this.dms = dms;
        this.users = users;
    }

    public List<ChannelDTO> getChannels() {
        return channels;
    }

    public List<ChannelDTO> getDms() {
        return dms;
    }

    public List<UserDTO> getUsers() {
        return users;
    }
}
