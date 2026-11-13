/**
 * BYD Watch GT 4 - Pure JS Car State & Protocol Module
 * Handles local state creation, climate temperature stepping,
 * Phone <-> Watch JSON command serialization, and response parsing.
 *
 * Designed with zero DOM / HarmonyOS globals for clean unit testing under Node.js.
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

let commandCounter = 0;

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
  const current = (state && typeof state.targetTempC === 'number' && !Number.isNaN(state.targetTempC))
    ? state.targetTempC
    : MIN_TEMP_C;

  if (typeof delta !== 'number' || Number.isNaN(delta)) {
    return {
      ...(state || {}),
      targetTempC: current
    };
  }

  const raw = current + delta;
  const clamped = Math.max(MIN_TEMP_C, Math.min(MAX_TEMP_C, raw));

  return {
    ...(state || {}),
    targetTempC: clamped
  };
}

/**
 * Builds a JSON command string matching the Phone <-> Watch P2P contract.
 *
 * @param {string} action - Action name ('status', 'lock', 'unlock', 'ac_on', 'ac_off', 'lights', 'horn').
 * @param {object} [state] - Current state (used for targetTempC when action is 'ac_on').
 * @returns {string} Serialized JSON command string.
 */
export function buildCommand(action, state) {
  const id = `${Date.now()}_${++commandCounter}`;
  const payload = {
    id,
    action
  };

  if (action === 'ac_on') {
    const tempC = (state && typeof state.targetTempC === 'number' && !Number.isNaN(state.targetTempC))
      ? state.targetTempC
      : MIN_TEMP_C;
    payload.tempC = tempC;
  }

  return JSON.stringify(payload);
}

/**
 * Parses and applies a response payload received from the companion phone.
 * Updates telemetry, clears pending flags, sets user-friendly error banners,
 * and safely ignores malformed or non-object payloads without throwing.
 * Does not mutate the input state.
 *
 * @param {object} state - Current car state.
 * @param {string} rawJson - Raw JSON string from Wear Engine P2P bridge.
 * @returns {object} New updated car state.
 */
export function applyResponse(state, rawJson) {
  const currentState = state || createInitialState();

  if (!rawJson || typeof rawJson !== 'string') {
    return currentState;
  }

  let parsed;
  try {
    parsed = JSON.parse(rawJson);
  } catch (_err) {
    return currentState;
  }

  if (!parsed || typeof parsed !== 'object' || Array.isArray(parsed)) {
    return currentState;
  }

  const nextState = { ...currentState };
  nextState.phoneOnline = true;
  nextState.pending = false;
  nextState.confirmUnlock = false;

  if (parsed.ok === false) {
    if (parsed.err && ERROR_BANNERS[parsed.err]) {
      nextState.banner = ERROR_BANNERS[parsed.err];
    } else if (parsed.msg && typeof parsed.msg === 'string') {
      nextState.banner = parsed.msg;
    } else if (parsed.err && typeof parsed.err === 'string') {
      nextState.banner = parsed.err;
    } else {
      nextState.banner = 'Error';
    }
    return nextState;
  }

  // ok === true or default success: clear banner
  nextState.banner = '';

  const data = (parsed.state && typeof parsed.state === 'object' && !Array.isArray(parsed.state))
    ? parsed.state
    : parsed;

  if (typeof data.soc === 'number' && !Number.isNaN(data.soc)) {
    nextState.soc = data.soc;
  }
  if (typeof data.rangeKm === 'number' && !Number.isNaN(data.rangeKm)) {
    nextState.rangeKm = data.rangeKm;
  }
  if (typeof data.locked === 'boolean') {
    nextState.locked = data.locked;
  }
  if (typeof data.acOn === 'boolean') {
    nextState.acOn = data.acOn;
  }
  if (typeof data.cabinTempC === 'number' && !Number.isNaN(data.cabinTempC)) {
    nextState.cabinTempC = data.cabinTempC;
  }
  if (
    typeof data.targetTempC === 'number' &&
    !Number.isNaN(data.targetTempC) &&
    data.targetTempC >= MIN_TEMP_C &&
    data.targetTempC <= MAX_TEMP_C
  ) {
    nextState.targetTempC = data.targetTempC;
  }
  if (typeof data.ts === 'number' && !Number.isNaN(data.ts)) {
    nextState.ts = data.ts;
  }

  return nextState;
}
