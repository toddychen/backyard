package com.backyard.playground.data.model.driver;

import java.util.List;
import java.util.UUID;

public class MembershipUpdateDTO {

    private List<DriverPositionDTO> entered;
    private List<UUID> left;

    public MembershipUpdateDTO(List<DriverPositionDTO> entered, List<UUID> left) {
        this.entered = entered;
        this.left = left;
    }

    public List<DriverPositionDTO> getEntered() { return entered; }
    public void setEntered(List<DriverPositionDTO> entered) { this.entered = entered; }
    public List<UUID> getLeft() { return left; }
    public void setLeft(List<UUID> left) { this.left = left; }
}
