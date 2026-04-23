package com.backyard.playground.data.model.driver;

import java.util.List;

public class PositionUpdateDTO {

    private List<DriverPositionDTO> drivers;

    public PositionUpdateDTO(List<DriverPositionDTO> drivers) {
        this.drivers = drivers;
    }

    public List<DriverPositionDTO> getDrivers() { return drivers; }
    public void setDrivers(List<DriverPositionDTO> drivers) { this.drivers = drivers; }
}
