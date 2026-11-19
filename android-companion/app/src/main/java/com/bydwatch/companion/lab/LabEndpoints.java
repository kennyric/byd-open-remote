package com.bydwatch.companion.lab;

/** Cloud paths reachable from the trial Lab. */
public final class LabEndpoints {

    public static final String GET_AC_LIMIT = "/control/smartCharge/getAcCurrentLimit";
    public static final String SET_AC_LIMIT = "/control/smartCharge/limitCurrentControl";
    public static final String LIMIT_CURRENT_RESULT = "/control/smartCharge/limitCurrentControlResult";
    public static final String HOME_PAGE = "/control/smartCharge/homePage";
    public static final String CHANGE_CHARGE_STATUS = "/control/smartCharge/changeChargeStatue";
    public static final String CHANGE_RESULT = "/control/smartCharge/changeResult";
    public static final String SAVE_SCHEDULE = "/control/smartCharge/saveOrUpdate";
    public static final String CHARGE_SOC_LIMIT = "/control/smartCharge/chargeSocLimit";
    public static final String STOP_CHARGE = "/control/smartCharge/stopChargeControl";
    public static final String CONTINUE_CHARGE = "/control/smartCharge/continueChargeControl";

    private static final String[] RAW_RUNNER_PATHS = {
            GET_AC_LIMIT,
            SET_AC_LIMIT,
            LIMIT_CURRENT_RESULT,
            HOME_PAGE,
            CHANGE_CHARGE_STATUS,
            CHANGE_RESULT,
            SAVE_SCHEDULE,
            CHARGE_SOC_LIMIT,
            STOP_CHARGE,
            CONTINUE_CHARGE
    };

    private LabEndpoints() {}

    public static String[] rawRunnerPaths() {
        return RAW_RUNNER_PATHS.clone();
    }
}
