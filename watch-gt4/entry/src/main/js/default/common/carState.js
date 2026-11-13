/**
 * BYD Watch GT 4 - Pure JS Car State & Protocol Module
 * Handles local state creation, climate temperature stepping,
 * Phone <-> Watch JSON command serialization, and response parsing.
 *
 * ES5 / JerryScript compatible for HarmonyOS Lite Wearable (Watch GT 4).
 * Zero DOM / HarmonyOS globals for clean Node.js unit testing.
 */

export const MIN_TEMP_C = 17;
export const MAX_TEMP_C = 31;

const ERROR_BANNERS = {
  PIN_INVALID: 'Invalid PIN',
  AUTH_FAILED: 'Auth Failed',
  CAR_OFFLINE: 'Car Offline',
  NOT_CONFIGURED: 'Not Configured',
  TIMEOUT: 'Request Timeout'
};

var commandCounter = 0;

/**
 * Explicit ES5 shallow clone helper to avoid ES2018 object spread ({ ...obj })
 * in JerryScript environments without Babel.
 *
 * @param {object} src
 * @returns {object}
 */
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

/**
 * Checks if a value is a valid finite number.
 *
 * @param {*} val
 * @returns {boolean}
 */
function isFiniteNumber(val) {
  return typeof val === 'number' && isFinite(val);
}

/**
 * Clamps temperature to [MIN_TEMP_C, MAX_TEMP_C].
 *
 * @param {number} temp
 * @returns {number}
 */
function clampTemp(temp) {
  if (temp < MIN_TEMP_C) {
    return MIN_TEMP_C;
  }
  if (temp > MAX_TEMP_C) {
    return MAX_TEMP_C;
  }
  return temp;
}

/**
 * Creates the initial default vehicle and UI state for the watch.
 * @returns {object} Initial state object.
 */
export function createInitialState() {
  return {
    soc: null,
    rangeKm: null,
    locked: true,
    acOn: false,
    cabinTempC: null,
    targetTempC: MIN_TEMP_C,
    ts: null,
    pending: false,
    confirmUnlock: false,
    phoneOnline: false,
    banner: ''
  };
}

/**
 * Adjusts target climate temperature by delta (°C) within [17, 31].
 * Does not mutate the input state.
 *
 * @param {object} state - Current car state.
 * @param {number} delta - Temperature increment or decrement in °C.
 * @returns {object} New state with adjusted targetTempC.
 */
export function stepTemp(state, delta) {
  var current = (state && isFiniteNumber(state.targetTempC))
    ? clampTemp(state.targetTempC)
    : MIN_TEMP_C;

  var out = cloneObj(state);

  if (!isFiniteNumber(delta)) {
    out.targetTempC = current;
    return out;
  }

  out.targetTempC = clampTemp(current + delta);
  return out;
}

/**
 * Builds a JSON command string matching the Phone <-> Watch P2P contract.
 *
 * @param {string} action - Action name ('status', 'lock', 'unlock', 'ac_on', 'ac_off', 'lights', 'horn').
 * @param {object} [state] - Current state (used for targetTempC when action is 'ac_on').
 * @returns {string} Serialized JSON command string.
 */
export function buildCommand(action, state) {
  commandCounter += 1;
  var id = String(Date.now()) + '_' + String(commandCounter);
  var payload = {
    id: id,
    action: action
  };

  if (action === 'ac_on') {
    var tempC = MIN_TEMP_C;
    if (state && isFiniteNumber(state.targetTempC)) {
      tempC = clampTemp(state.targetTempC);
    }
    payload.tempC = tempC;
  }

  return JSON.stringify(payload);
}

/**
 * Parses and applies a response payload received from the companion phone.
 * Updates telemetry, clears pending flags, sets user-friendly error banners,
 * and safely ignores malformed, non-object, or stale (mismatched ID) payloads.
 * Does not mutate the input state.
 *
 * @param {object} state - Current car state.
 * @param {string} rawJson - Raw JSON string from Wear Engine P2P bridge.
 * @param {string} [expectedId] - Optional command ID to correlate with this response.
 * @returns {object} New updated car state.
 */
export function applyResponse(state, rawJson, expectedId) {
  var currentState = state || createInitialState();

  if (!rawJson || typeof rawJson !== 'string') {
    return currentState;
  }

  var parsed;
  try {
    parsed = JSON.parse(rawJson);
  } catch (_err) {
    return currentState;
  }

  if (!parsed || typeof parsed !== 'object' || Array.isArray(parsed)) {
    return currentState;
  }

  // Id correlation check: if caller provided an expectedId, reject mismatched responses
  if (expectedId !== undefined && expectedId !== null) {
    if (parsed.id !== undefined && parsed.id !== null) {
      if (String(parsed.id) !== String(expectedId)) {
        return currentState;
      }
    }
  }

  var nextState = cloneObj(currentState);
  nextState.phoneOnline = true;
  nextState.pending = false;
  nextState.confirmUnlock = false;

  if (parsed.ok === false) {
    if (
      typeof parsed.err === 'string' &&
      Object.prototype.hasOwnProperty.call(ERROR_BANNERS, parsed.err)
    ) {
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

  // ok === true or default success: clear banner
  nextState.banner = '';

  var data = (parsed.state && typeof parsed.state === 'object' && !Array.isArray(parsed.state))
    ? parsed.state
    : parsed;

  if (isFiniteNumber(data.soc)) {
    nextState.soc = data.soc;
  }
  if (isFiniteNumber(data.rangeKm)) {
    nextState.rangeKm = data.rangeKm;
  }
  if (typeof data.locked === 'boolean') {
    nextState.locked = data.locked;
  }
  if (typeof data.acOn === 'boolean') {
    nextState.acOn = data.acOn;
  }

  // Cabin temperature: supports numeric values (including 0 and negative) and explicit null reset
  if (isFiniteNumber(data.cabinTempC)) {
    nextState.cabinTempC = data.cabinTempC;
  } else if (data.cabinTempC === null) {
    nextState.cabinTempC = null;
  }

  // Target temperature: only accept valid numbers within [MIN_TEMP_C, MAX_TEMP_C]; reject out-of-range
  if (
    isFiniteNumber(data.targetTempC) &&
    data.targetTempC >= MIN_TEMP_C &&
    data.targetTempC <= MAX_TEMP_C
  ) {
    nextState.targetTempC = data.targetTempC;
  }

  if (isFiniteNumber(data.ts)) {
    nextState.ts = data.ts;
  }

  return nextState;
}
