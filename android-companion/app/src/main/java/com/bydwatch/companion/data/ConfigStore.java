package com.bydwatch.companion.data;

/**
 * Storage interface for BYD user credentials and app configuration.
 */
public interface ConfigStore {

    String getUsername();
    void setUsername(String username);

    String getPassword();
    void setPassword(String password);

    String getPin();
    void setPin(String pin);

    String getVin();
    void setVin(String vin);

    String getRegion();
    void setRegion(String region);

    int getDefaultTempC();
    void setDefaultTempC(int tempC);

    boolean isConfigured();

    void clear();
}
