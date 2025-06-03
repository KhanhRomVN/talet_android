package com.kr.talet;

public class DeviceItem {
    private String name;
    private String ip;

    public DeviceItem(String name, String ip) {
        this.name = name;
        this.ip = ip;
    }

    public String getName() { return name; }
    public String getIp() { return ip; }
}