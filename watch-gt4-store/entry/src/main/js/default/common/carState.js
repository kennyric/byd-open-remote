/**
 * EV Watch Link (Store Edition) - Pure JS State & Protocol Module
 * Zero automaker trademarks/brand strings. ES5 / JerryScript compatible.
 */

export const MIN_TEMP_C = 17;
export const MAX_TEMP_C = 31;
export const MIN_TEMP_GEAR = 1;
export const MAX_TEMP_GEAR = 9;
export const DEFAULT_TEMP_GEAR = 3;
export const MIN_AC_DURATION_MIN = 10;
export const MAX_AC_DURATION_MIN = 30;
export const DEFAULT_AC_DURATION_MIN = 20;
export const AC_DURATIONS = [10, 15, 20, 25, 30];

const ERROR_BANNERS = {
  PIN_INVALID: 'Invalid PIN',
  AUTH_FAILED: 'Auth Failed',
  CAR_OFFLINE: 'Car Offline',
  NOT_CONFIGURED: 'Not Configured',
  TIMEOUT: 'Request Timeout'
};

var commandCounter = 0;

function cloneObj(src) {
  var out = {};
  if (src && typeof src === 'object') {
    for (var k in src) {
      if (Object.prototype.hasOwnProperty.call(src, k)) {
        out[k] = src[k];
      }
    }
  }
  return out;
}

function isFiniteNumber(val) {
  return typeof val === 'number' && isFinite(val);
}

function clampTemp(temp) {
  if (temp < MIN_TEMP_C) return MIN_TEMP_C;
  if (temp > MAX_TEMP_C) return MAX_TEMP_C;
  return temp;
}

function clampTempGear(gear) {
  if (gear < MIN_TEMP_GEAR) return MIN_TEMP_GEAR;
  if (gear > MAX_TEMP_GEAR) return MAX_TEMP_GEAR;
  return Math.round(gear);
}

function firstFinite(obj, primaryKey, fallbackKey) {
  if (isFiniteNumber(obj[primaryKey])) return obj[primaryKey];
  if (fallbackKey && isFiniteNumber(obj[fallbackKey])) return obj[fallbackKey];
  return null;
}

function firstBool(obj, primaryKey, fallbackKey) {
  if (typeof obj[primaryKey] === 'boolean') return obj[primaryKey];
  if (fallbackKey && typeof obj[fallbackKey] === 'boolean') return obj[fallbackKey];
  return null;
}

export function normalizeAcDuration(raw) {
  if (!isFiniteNumber(raw)) return DEFAULT_AC_DURATION_MIN;
  var rounded = Math.round(raw);
  if (rounded < MIN_AC_DURATION_MIN) return MIN_AC_DURATION_MIN;
  if (rounded > MAX_AC_DURATION_MIN) return MAX_AC_DURATION_MIN;
  return rounded;
}

export function cycleAcDuration(state) {
  var cur = (state && isFiniteNumber(state.acDurationMin))
    ? normalizeAcDuration(state.acDurationMin)
    : DEFAULT_AC_DURATION_MIN;
  var nextVal = AC_DURATIONS[0];
  for (var i = 0; i < AC_DURATIONS.length; i += 1) {
    if (AC_DURATIONS[i] > cur) {
      nextVal = AC_DURATIONS[i];
      break;
    }
  }
  var out = cloneObj(state);
  out.acDurationMin = nextVal;
  return out;
}

export function normalizeDistanceUnit(raw) {
  return (raw === 'mi') ? 'mi' : 'km';
}

export function normalizeTempUnit(raw) {
  return (raw === 'F') ? 'F' : 'C';
}

export function normalizePressureUnit(raw) {
  if (raw === 'PSI' || raw === 'psi') return 'PSI';
  if (raw === 'bar' || raw === 'BAR') return 'bar';
  return 'kPa';
}

export function formatDistance(km, distUnit) {
  var u = normalizeDistanceUnit(distUnit);
  if (!isFiniteNumber(km)) return '-- ' + u;
  var val = u === 'mi' ? Math.round(km * 0.621371) : Math.round(km);
  return String(val) + ' ' + u;
}

export function formatTemp(tempC, tempUnit) {
  var u = normalizeTempUnit(tempUnit);
  if (!isFiniteNumber(tempC)) return '--°' + u;
  var val = u === 'F' ? Math.round(tempC * 9 / 5 + 32) : Math.round(tempC);
  return String(val) + '°' + u;
}

export function formatPressure(kpa, presUnit) {
  var u = normalizePressureUnit(presUnit);
  if (!isFiniteNumber(kpa) || kpa <= 0) return '--';
  if (u === 'PSI') return (kpa * 0.1450377).toFixed(1) + ' PSI';
  if (u === 'bar') return (kpa / 100).toFixed(2) + ' bar';
  return String(Math.round(kpa)) + ' kPa';
}

export function createInitialState() {
  return {
    soc: null,
    rangeKm: null,
    locked: true,
    acOn: false,
    manualAc: false,
    cabinTempC: null,
    targetTempC: MIN_TEMP_C,
    tempGear: DEFAULT_TEMP_GEAR,
    acDurationMin: DEFAULT_AC_DURATION_MIN,
    fanLevel: 0,
    windMode: 0,
    ts: null,
    pending: false,
    confirmUnlock: false,
    phoneOnline: false,
    banner: '',
    vin: '',
    modelName: 'EV REMOTE',
    carOn: false,
    chargingState: -1,
    chargingKw: null,
    chargeRemainingMinutes: null,
    doorsOpenCount: 0,
    windowsOpenCount: 0,
    trunkOpen: false,
    totalMileageKm: null,
    outsideTempC: null,
    fuelPercent: null,
    fuelKm: null,
    tireLfKpa: null,
    tireRfKpa: null,
    tireLrKpa: null,
    tireRrKpa: null,
    batteryHeatOn: false,
    driverSeatLevel: 0,
    canWindows: true,
    canBatteryHeat: false,
    canSeatClimate: false,
    distanceUnit: 'km',
    tempUnit: 'C',
    pressureUnit: 'kPa',
    lat: null,
    lng: null,
    gpsTs: null,
    vehicles: []
  };
}

export function stepTemp(state, delta) {
  var out = cloneObj(state);
  if (state && state.manualAc) {
    var currentGear = isFiniteNumber(state.tempGear)
      ? clampTempGear(state.tempGear)
      : DEFAULT_TEMP_GEAR;
    out.tempGear = isFiniteNumber(delta) ? clampTempGear(currentGear + delta) : currentGear;
    return out;
  }
  var current = (state && isFiniteNumber(state.targetTempC))
    ? clampTemp(state.targetTempC)
    : MIN_TEMP_C;
  if (!isFiniteNumber(delta)) {
    out.targetTempC = current;
    return out;
  }
  out.targetTempC = clampTemp(current + delta);
  return out;
}

export function buildCommand(action, state, extra) {
  commandCounter += 1;
  var id = String(Date.now()) + '_' + String(commandCounter);
  var payload = { id: id, action: action };

  if (action === 'ac_on') {
    var tempC = MIN_TEMP_C;
    if (state && isFiniteNumber(state.targetTempC)) {
      tempC = clampTemp(state.targetTempC);
    }
    payload.tempC = tempC;
    if (extra && isFiniteNumber(extra.acDurationMin)) {
      payload.acDurationMin = normalizeAcDuration(extra.acDurationMin);
    } else if (state && isFiniteNumber(state.acDurationMin)) {
      payload.acDurationMin = normalizeAcDuration(state.acDurationMin);
    } else {
      payload.acDurationMin = DEFAULT_AC_DURATION_MIN;
    }
    if (extra && isFiniteNumber(extra.tempGear)) {
      payload.tempGear = clampTempGear(extra.tempGear);
    } else if (state && state.manualAc) {
      payload.tempGear = isFiniteNumber(state.tempGear)
        ? clampTempGear(state.tempGear)
        : DEFAULT_TEMP_GEAR;
    }
    var reqFan = (extra && isFiniteNumber(extra.fanLevel)) ? extra.fanLevel : (state ? state.fanLevel : null);
    if (state && state.manualAc && isFiniteNumber(reqFan) && reqFan >= 1 && reqFan <= 7) {
      payload.fanLevel = Math.round(reqFan);
    }
    var reqWind = (extra && isFiniteNumber(extra.windMode)) ? extra.windMode : (state ? state.windMode : null);
    if (state && state.manualAc && isFiniteNumber(reqWind) && reqWind >= 1 && reqWind <= 5) {
      payload.windMode = Math.round(reqWind);
    }
  } else if (action === 'seat_heat') {
    if (extra && isFiniteNumber(extra.level)) {
      payload.level = extra.level;
    } else if (state && isFiniteNumber(state.driverSeatLevel)) {
      payload.level = (state.driverSeatLevel + 1) % 3;
    } else {
      payload.level = 1;
    }
  } else if (action === 'switch_car') {
    if (extra && typeof extra.vin === 'string' && extra.vin.length > 0) {
      payload.vin = extra.vin;
    }
  }

  return JSON.stringify(payload);
}

export function applyResponse(state, rawJson, expectedId) {
  var currentState = state || createInitialState();
  if (!rawJson || typeof rawJson !== 'string') return currentState;

  var parsed;
  try {
    parsed = JSON.parse(rawJson);
  } catch (_err) {
    return currentState;
  }

  if (!parsed || typeof parsed !== 'object' || Array.isArray(parsed)) {
    return currentState;
  }

  if (expectedId !== undefined && expectedId !== null) {
    if (parsed.id === undefined || parsed.id === null || String(parsed.id) !== String(expectedId)) {
      return currentState;
    }
  }

  var nextState = cloneObj(currentState);
  nextState.phoneOnline = true;
  nextState.pending = false;
  nextState.confirmUnlock = false;

  if (parsed.ok === false) {
    if (typeof parsed.err === 'string' && Object.prototype.hasOwnProperty.call(ERROR_BANNERS, parsed.err)) {
      nextState.banner = ERROR_BANNERS[parsed.err];
    } else if (typeof parsed.msg === 'string' && parsed.msg.length > 0) {
      nextState.banner = parsed.msg;
    } else if (typeof parsed.err === 'string' && parsed.err.length > 0) {
      nextState.banner = parsed.err;
    } else {
      nextState.banner = 'Error';
    }
    return nextState;
  }

  nextState.banner = '';
  var data = (parsed.state && typeof parsed.state === 'object' && !Array.isArray(parsed.state))
    ? parsed.state
    : parsed;

  if (isFiniteNumber(data.soc)) nextState.soc = data.soc;
  if (isFiniteNumber(data.rangeKm)) nextState.rangeKm = data.rangeKm;
  if (typeof data.locked === 'boolean') nextState.locked = data.locked;
  if (typeof data.acOn === 'boolean') nextState.acOn = data.acOn;

  if (isFiniteNumber(data.cabinTempC)) {
    nextState.cabinTempC = data.cabinTempC;
  } else if (data.cabinTempC === null) {
    nextState.cabinTempC = null;
  }

  if (isFiniteNumber(data.targetTempC) && data.targetTempC >= MIN_TEMP_C && data.targetTempC <= MAX_TEMP_C) {
    nextState.targetTempC = data.targetTempC;
  }
  if (typeof data.manAc === 'boolean') nextState.manualAc = data.manAc;
  if (isFiniteNumber(data.tempGear) && data.tempGear >= MIN_TEMP_GEAR && data.tempGear <= MAX_TEMP_GEAR) {
    nextState.tempGear = Math.round(data.tempGear);
  }

  if (isFiniteNumber(data.ts)) nextState.ts = data.ts;
  if (typeof data.vin === 'string') nextState.vin = data.vin;
  if (typeof data.modelName === 'string' && data.modelName.length > 0) {
    nextState.modelName = data.modelName;
  }

  var chgVal = firstFinite(data, 'charging', 'chargingState');
  if (chgVal !== null) nextState.chargingState = chgVal;
  if (isFiniteNumber(data.chgKw)) nextState.chargingKw = data.chgKw;
  if (typeof data.carOn === 'boolean') nextState.carOn = data.carOn;

  var chgMinVal = firstFinite(data, 'chgMin', 'chargeRemainingMinutes');
  if (chgMinVal !== null) nextState.chargeRemainingMinutes = chgMinVal;

  var doorsVal = firstFinite(data, 'openDoors', 'doorsOpen');
  if (doorsVal !== null) nextState.doorsOpenCount = doorsVal;
  var winsVal = firstFinite(data, 'openWins', 'winsOpen');
  if (winsVal !== null) nextState.windowsOpenCount = winsVal;
  if (typeof data.trunkOpen === 'boolean') nextState.trunkOpen = data.trunkOpen;
  if (isFiniteNumber(data.odoKm)) nextState.totalMileageKm = data.odoKm;
  if (isFiniteNumber(data.outTempC)) nextState.outsideTempC = data.outTempC;
  if (isFiniteNumber(data.fuelPct)) nextState.fuelPercent = data.fuelPct;
  if (isFiniteNumber(data.fuelKm)) nextState.fuelKm = data.fuelKm;

  if (Array.isArray(data.tpms)) {
    if (isFiniteNumber(data.tpms[0])) nextState.tireLfKpa = data.tpms[0];
    if (isFiniteNumber(data.tpms[1])) nextState.tireRfKpa = data.tpms[1];
    if (isFiniteNumber(data.tpms[2])) nextState.tireLrKpa = data.tpms[2];
    if (isFiniteNumber(data.tpms[3])) nextState.tireRrKpa = data.tpms[3];
  }
  if (isFiniteNumber(data.tpmsLf)) nextState.tireLfKpa = data.tpmsLf;
  if (isFiniteNumber(data.tpmsRf)) nextState.tireRfKpa = data.tpmsRf;
  if (isFiniteNumber(data.tpmsLr)) nextState.tireLrKpa = data.tpmsLr;
  if (isFiniteNumber(data.tpmsRr)) nextState.tireRrKpa = data.tpmsRr;

  var batVal = firstBool(data, 'batHeat', 'batHeatOn');
  if (batVal !== null) nextState.batteryHeatOn = batVal;
  var seatVal = firstFinite(data, 'seat', 'seatLvl');
  if (seatVal !== null) nextState.driverSeatLevel = seatVal;
  var canWinVal = firstBool(data, 'capWin', 'canWin');
  if (canWinVal !== null) nextState.canWindows = canWinVal;
  var canBatVal = firstBool(data, 'capBatHeat', 'canBat');
  if (canBatVal !== null) nextState.canBatteryHeat = canBatVal;
  var canSeatVal = firstBool(data, 'capSeat', 'canSeat');
  if (canSeatVal !== null) nextState.canSeatClimate = canSeatVal;
  if (isFiniteNumber(data.fanLevel)) nextState.fanLevel = Math.round(data.fanLevel);
  if (isFiniteNumber(data.windMode)) nextState.windMode = Math.round(data.windMode);
  if (isFiniteNumber(data.acDurationMin)) {
    nextState.acDurationMin = normalizeAcDuration(data.acDurationMin);
  }
  if (isFiniteNumber(data.lat)) nextState.lat = data.lat;
  if (isFiniteNumber(data.lng)) nextState.lng = data.lng;
  if (isFiniteNumber(data.gpsTs)) nextState.gpsTs = data.gpsTs;

  if (typeof data.uDist === 'string') nextState.distanceUnit = normalizeDistanceUnit(data.uDist);
  if (typeof data.uTemp === 'string') nextState.tempUnit = normalizeTempUnit(data.uTemp);
  if (typeof data.uPres === 'string') nextState.pressureUnit = normalizePressureUnit(data.uPres);

  if (Array.isArray(data.cars)) {
    nextState.vehicles = data.cars.slice(0, 5);
  }

  return nextState;
}
