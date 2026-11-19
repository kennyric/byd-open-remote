import {
  createInitialState,
  stepTemp,
  cycleAcDuration,
  normalizeAcDuration,
  buildCommand,
  applyResponse,
  formatDistance,
  formatTemp,
  formatPressure
} from '../../common/carState.js';
import {
  Builder,
  getP2pClient
} from '../../wearengine/wearengine.js';

export const COMPANION_PKG = 'com.bydopenremote.app';
export const COMPANION_FINGERPRINT = 'd1670d073c1a8542b780802adfcc41de58875bc598adcc7644d8cc8f155cb899';
export const COMMAND_TIMEOUT_MS = 15000;
export const BANNER_TIMEOUT_MS = 3500;
export const CLIMATE_DEBOUNCE_MS = 700;
export const EXIT_SWIPE_MIN_PX = 80;

function loadSystemApp() {
  try {
    if (typeof system !== 'undefined' && system.app) {
      return system.app;
    }
    if (typeof require !== 'undefined') {
      return require('@system.app');
    }
  } catch (_e) {
    // Fallback for test environments without Lite runtime
  }
  return null;
}

function extractTouchPoint(e) {
  if (!e) {
    return null;
  }
  var list = (e.changedTouches && e.changedTouches.length)
    ? e.changedTouches
    : (e.touches && e.touches.length ? e.touches : null);
  var pt = list ? list[0] : e;
  if (!pt) {
    return null;
  }
  var x = typeof pt.globalX === 'number'
    ? pt.globalX
    : (typeof pt.clientX === 'number' ? pt.clientX : pt.localX);
  var y = typeof pt.globalY === 'number'
    ? pt.globalY
    : (typeof pt.clientY === 'number' ? pt.clientY : pt.localY);
  if (typeof x !== 'number' || !isFinite(x)) {
    return null;
  }
  return { x: x, y: (typeof y === 'number' && isFinite(y)) ? y : 0 };
}

var SUCCESS_BANNERS = {
  lock: 'Car Locked',
  unlock: 'Car Unlocked',
  ac_on: 'AC Started',
  ac_off: 'AC Stopped',
  lights: 'Lights Flashed',
  horn: 'Horn Sounded',
  vent_windows: 'Win Vented',
  close_windows: 'Win Closed',
  bat_heat_on: 'Bat Heat On',
  bat_heat_off: 'Bat Heat Off',
  seat_heat: 'Seat Updated',
  switch_car: 'Car Switched'
};

export function createPageDefinition(deps) {
  var clientFactory = (deps && deps.getP2pClient) || getP2pClient;
  var systemApp = (deps && deps.systemApp) || loadSystemApp();

  return {
    data: {
      state: createInitialState(),
      modelTitle: 'ATTO 1',
      confirmTitle: 'Unlock ATTO 1?',
      socPercent: 0,
      socArcColor: '#10b981',
      socText: '--%',
      rangeText: '-- km',
      locked: true,
      lockText: 'Locked',
      lockTextColor: '#10b981',
      lockStatusIcon: '/common/icons/lock.png',
      primaryBtnIcon: '/common/icons/unlock.png',
      primaryBtnText: 'Unlock Car',
      primaryBtnBg: '#0b2922',
      primaryBtnColor: '#10b981',
      hasCabinTemp: false,
      cabinTempText: '',
      acOn: false,
      targetTempC: 17,
      targetTempText: '17°C',
      acCircleLabel: 'AC 17°',
      acCircleBg: '#131b24',
      acCircleBorder: '#223042',
      acCircleTextColor: '#e2e8f0',
      acPillBg: '#0e141b',
      acPillTextColor: '#38bdf8',
      acPillLabel: 'Start AC',
      acDurationLabel: 'Timer 20m',
      phoneOnline: false,
      hubBg: '#f59e0b',
      phoneLinkText: 'Waiting for phone...',
      phoneLinkColor: '#f59e0b',
      lastSyncText: 'Not synced',

      // Page 4 Comfort
      batHeatLabel: 'Bat Heat',
      batHeatBg: '#131b24',
      batHeatBorder: '#223042',
      batHeatTextColor: '#e2e8f0',
      seatHeatLabel: 'Seat Off',
      seatHeatBg: '#131b24',
      seatHeatBorder: '#223042',
      seatHeatTextColor: '#e2e8f0',

      // Page 5 Vehicle Health & TPMS
      healthSummaryText: 'All Closed',
      healthSummaryColor: '#10b981',
      healthSummaryBorder: '#10b981',
      healthSummaryBg: '#0b2922',
      tpmsLfText: '--',
      tpmsRfText: '--',
      tpmsLrText: '--',
      tpmsRrText: '--',
      healthFooterText: 'Odo --',

      // Page 6 My Garage (up to 3 car slots)
      hasMultipleCars: false,
      car0Visible: true,
      car0Name: 'ATTO 1',
      car0Sub: 'Active Vehicle',
      car0Bg: '#0b2922',
      car0Border: '#10b981',
      car0Tag: 'ACTIVE',
      car1Visible: false,
      car1Name: '',
      car1Sub: '',
      car1Bg: '#131b24',
      car1Border: '#223042',
      car1Tag: 'TAP',
      car2Visible: false,
      car2Name: '',
      car2Sub: '',
      car2Bg: '#131b24',
      car2Border: '#223042',
      car2Tag: 'TAP',

      confirmUnlock: false,
      showBanner: false,
      bannerText: '',
      bannerBg: '#0e141b',
      bannerBorder: '#10b981',
      bannerIsError: false
    },

    _p2pClient: null,
    _pendingId: null,
    _pendingAction: null,
    _timeoutHandle: null,
    _bannerHandle: null,
    _climateDebounceHandle: null,
    _currentPage: 0,
    _touchStartPage: null,
    _touchStartX: null,
    _touchStartY: null,

    onInit: function() {
      this._currentPage = 0;
      this._initState();
      this._initWearEngine();
    },

    onShow: function() {
      this._syncSwipeToDismiss();
      this.sendAction('status');
    },

    onHide: function() {
      this._setSwipeToDismiss(false);
    },

    onDestroy: function() {
      this._setSwipeToDismiss(false);
      this._clearCommandTimer();
      this._clearBannerTimer();
      this._clearClimateDebounceTimer();
      if (this._p2pClient && typeof this._p2pClient.unregisterReceiver === 'function') {
        this._p2pClient.unregisterReceiver();
      }
    },

    onSwiperChange: function(e) {
      var idx = (e && typeof e.index === 'number') ? e.index : 0;
      this._currentPage = idx;
      this._resetTouchTracking();
      this._syncSwipeToDismiss();
    },

    onMainTouchStart: function(e) {
      var pt = extractTouchPoint(e);
      this._touchStartPage = this._currentPage;
      this._touchStartX = pt ? pt.x : null;
      this._touchStartY = pt ? pt.y : null;
    },

    onMainTouchEnd: function(e) {
      var startedOnMain = this._touchStartPage === 0;
      var startX = this._touchStartX;
      var startY = this._touchStartY;
      this._resetTouchTracking();

      if (!startedOnMain || this._currentPage !== 0 || this.state.confirmUnlock || startX === null) {
        return;
      }
      var pt = extractTouchPoint(e);
      if (!pt) {
        return;
      }
      var dx = pt.x - startX;
      var dy = pt.y - (startY !== null ? startY : 0);
      if (dx >= EXIT_SWIPE_MIN_PX && Math.abs(dx) > Math.abs(dy) * 1.4) {
        this._exitApp();
      }
    },

    onMainSwipe: function(e) {
      var startedOnMain = (this._touchStartPage === null || this._touchStartPage === 0);
      this._resetTouchTracking();
      if (!startedOnMain || this._currentPage !== 0 || this.state.confirmUnlock) {
        return;
      }
      if (e && e.direction === 'right') {
        this._exitApp();
      }
    },

    _resetTouchTracking: function() {
      this._touchStartPage = null;
      this._touchStartX = null;
      this._touchStartY = null;
    },

    _syncSwipeToDismiss: function() {
      this._setSwipeToDismiss(this._currentPage === 0);
    },

    _setSwipeToDismiss: function(enabled) {
      if (systemApp && typeof systemApp.setSwipeToDismiss === 'function') {
        systemApp.setSwipeToDismiss(Boolean(enabled));
      }
    },

    _exitApp: function() {
      if (systemApp && typeof systemApp.terminate === 'function') {
        systemApp.terminate();
      }
    },

    _initState: function() {
      this.state = createInitialState();
      this._syncDerivedFields();
    },

    _initWearEngine: function() {
      var self = this;
      this._p2pClient = clientFactory();
      this._p2pClient.setPeerPkgName(COMPANION_PKG);
      this._p2pClient.setPeerFingerPrint(COMPANION_FINGERPRINT);
      this._p2pClient.registerReceiver({
        onSuccess: function() {
          // Receiver ready
        },
        onFailure: function() {
          self.state.phoneOnline = false;
          self._showBannerMessage('Bridge unavailable', true);
        },
        onReceiveMessage: function(message) {
          self.handleIncomingMessage(message);
        }
      });
    },

    handleIncomingMessage: function(message) {
      var raw = typeof message === 'string'
        ? message
        : (message && typeof message.data === 'string' ? message.data : '');
      if (!raw) {
        return;
      }

      var wasPendingAction = this._pendingAction;
      var prevPending = this.state.pending;
      var updated = applyResponse(this.state, raw, this._pendingId);

      if (updated === this.state) {
        return;
      }

      this._clearCommandTimer();
      this._pendingId = null;
      this._pendingAction = null;
      this.state = updated;

      if (updated.banner) {
        this._showBannerMessage(updated.banner, true);
      } else if (prevPending && wasPendingAction && SUCCESS_BANNERS[wasPendingAction]) {
        this._showBannerMessage(SUCCESS_BANNERS[wasPendingAction], false);
      } else {
        this._syncDerivedFields();
      }
    },

    onPrimaryLockTap: function() {
      if (this.state.pending) {
        return;
      }
      if (this.state.locked) {
        this.state.confirmUnlock = true;
        this._syncDerivedFields();
      } else {
        this.sendAction('lock');
      }
    },

    confirmUnlockAction: function() {
      this.state.confirmUnlock = false;
      this._syncDerivedFields();
      this.sendAction('unlock');
    },

    cancelUnlockAction: function() {
      this.state.confirmUnlock = false;
      this._syncDerivedFields();
    },

    onAcToggleTap: function() {
      this._clearClimateDebounceTimer();
      this.sendAction(this.state.acOn ? 'ac_off' : 'ac_on');
    },

    onLightsTap: function() {
      this.sendAction('lights');
    },

    onHornTap: function() {
      this.sendAction('horn');
    },

    onSyncTap: function() {
      this.sendAction('status');
    },

    onVentWindowsTap: function() {
      this.sendAction('vent_windows');
    },

    onCloseWindowsTap: function() {
      this.sendAction('close_windows');
    },

    onBatHeatTap: function() {
      this.sendAction(this.state.batteryHeatOn ? 'bat_heat_off' : 'bat_heat_on');
    },

    onSeatHeatTap: function() {
      var nextLvl = (this.state.driverSeatLevel + 1) % 3;
      this.sendAction('seat_heat', { level: nextLvl });
    },

    onSelectCar0: function() { this._selectCarAtIndex(0); },
    onSelectCar1: function() { this._selectCarAtIndex(1); },
    onSelectCar2: function() { this._selectCarAtIndex(2); },

    _selectCarAtIndex: function(idx) {
      var list = this.state.vehicles || [];
      if (idx < 0 || idx >= list.length) {
        return;
      }
      var target = list[idx];
      if (!target || !target.vin || target.vin === this.state.vin) {
        return;
      }
      this.sendAction('switch_car', { vin: target.vin });
    },

    onTempMinusTap: function() {
      this.state = stepTemp(this.state, -1);
      this._syncDerivedFields();
      this._scheduleClimateUpdateIfAcOn();
    },

    onTempPlusTap: function() {
      this.state = stepTemp(this.state, 1);
      this._syncDerivedFields();
      this._scheduleClimateUpdateIfAcOn();
    },

    onAcDurationTap: function() {
      this.state = cycleAcDuration(this.state);
      this._syncDerivedFields();
      this._scheduleClimateUpdateIfAcOn();
    },

    _clearClimateDebounceTimer: function() {
      if (this._climateDebounceHandle !== null) {
        clearTimeout(this._climateDebounceHandle);
        this._climateDebounceHandle = null;
      }
    },

    _scheduleClimateUpdateIfAcOn: function() {
      var self = this;
      this._clearClimateDebounceTimer();
      if (!this.state.acOn || this.state.pending) {
        return;
      }
      this._climateDebounceHandle = setTimeout(function() {
        self._climateDebounceHandle = null;
        if (self.state.acOn && !self.state.pending) {
          self.sendAction('ac_on');
        }
      }, CLIMATE_DEBOUNCE_MS);
    },

    sendAction: function(action, extra) {
      if (this.state.pending) {
        return;
      }

      var rawCmd = buildCommand(action, this.state, extra);
      var cmdId = null;
      try {
        cmdId = JSON.parse(rawCmd).id;
      } catch (_err) {
        cmdId = null;
      }

      this._clearCommandTimer();
      this._pendingId = cmdId;
      this._pendingAction = action;
      this.state.pending = true;
      this.state.banner = action === 'status' ? 'Syncing...' : 'Sending...';
      this.bannerIsError = false;
      this._syncDerivedFields();

      var self = this;
      this._timeoutHandle = setTimeout(function() {
        if (self.state.pending && self._pendingId === cmdId) {
          self._pendingId = null;
          self._pendingAction = null;
          self.state.pending = false;
          self.state.phoneOnline = false;
          self._showBannerMessage('Request Timeout', true);
        }
      }, COMMAND_TIMEOUT_MS);

      if (!this._p2pClient) {
        this._clearCommandTimer();
        this.state.pending = false;
        this._showBannerMessage('Bridge unavailable', true);
        return;
      }

      var msg = new Builder().setData(rawCmd).build();
      this._p2pClient.send(msg, {
        onSuccess: function() {
          // Sent over Bluetooth; keep waiting for JSON response payload
        },
        onFailure: function() {
          self._clearCommandTimer();
          self._pendingId = null;
          self._pendingAction = null;
          self.state.pending = false;
          self.state.phoneOnline = false;
          self._showBannerMessage('Phone Offline', true);
        }
      });
    },

    _showBannerMessage: function(text, isError) {
      var self = this;
      this._clearBannerTimer();
      this.state.banner = text;
      this.bannerIsError = Boolean(isError);
      this._syncDerivedFields();
      this._bannerHandle = setTimeout(function() {
        if (!self.state.pending && self.state.banner === text) {
          self.state.banner = '';
          self.bannerIsError = false;
          self._syncDerivedFields();
        }
      }, BANNER_TIMEOUT_MS);
    },

    _clearCommandTimer: function() {
      if (this._timeoutHandle !== null) {
        clearTimeout(this._timeoutHandle);
        this._timeoutHandle = null;
      }
    },

    _clearBannerTimer: function() {
      if (this._bannerHandle !== null) {
        clearTimeout(this._bannerHandle);
        this._bannerHandle = null;
      }
    },

    _syncDerivedFields: function() {
      var st = this.state;
      this.modelTitle = (typeof st.modelName === 'string' && st.modelName.length > 0)
        ? st.modelName
        : 'ATTO 1';
      this.confirmTitle = 'Unlock ' + this.modelTitle + '?';

      var socValid = typeof st.soc === 'number' && isFinite(st.soc);
      var clampedSoc = socValid ? Math.max(0, Math.min(100, Math.round(st.soc))) : 0;

      this.socPercent = clampedSoc;
      this.socArcColor = clampedSoc >= 30 ? '#10b981' : (clampedSoc >= 15 ? '#f59e0b' : '#ef4444');
      this.socText = socValid ? (String(clampedSoc) + '%') : '--%';
      if (typeof st.rangeKm === 'number' && isFinite(st.rangeKm)) {
        var baseRange = formatDistance(st.rangeKm, st.distanceUnit) + ' range';
        if (st.chargingState === 1) {
          var hasKw = typeof st.chargingKw === 'number' && isFinite(st.chargingKw) && st.chargingKw > 0;
          var hasMin = typeof st.chargeRemainingMinutes === 'number' && st.chargeRemainingMinutes > 0;
          if (hasKw) {
            this.rangeText = baseRange + ' · ' + st.chargingKw.toFixed(1) + 'kW' + (hasMin ? (' ' + String(Math.round(st.chargeRemainingMinutes)) + 'm') : '');
          } else {
            this.rangeText = baseRange + (hasMin ? (' · Chg ' + String(Math.round(st.chargeRemainingMinutes)) + 'm') : ' · Chg');
          }
        } else {
          this.rangeText = baseRange;
        }
      } else {
        this.rangeText = '-- ' + (st.distanceUnit === 'mi' ? 'mi' : 'km');
      }

      this.locked = Boolean(st.locked);
      this.lockText = (this.locked ? 'Locked' : 'Unlocked') + (st.carOn ? ' · On' : '');
      this.lockTextColor = this.locked ? '#10b981' : '#f59e0b';
      this.lockStatusIcon = this.locked ? '/common/icons/lock.png' : '/common/icons/unlock.png';
      this.primaryBtnIcon = this.locked ? '/common/icons/unlock.png' : '/common/icons/lock.png';
      this.primaryBtnText = this.locked ? 'Unlock Car' : 'Lock Car';
      this.primaryBtnBg = this.locked ? '#0b2922' : '#10b981';
      this.primaryBtnColor = this.locked ? '#10b981' : '#0a0e14';

      var hasCabin = typeof st.cabinTempC === 'number' && isFinite(st.cabinTempC);
      this.hasCabinTemp = hasCabin;
      this.cabinTempText = hasCabin ? formatTemp(st.cabinTempC, st.tempUnit) : '';

      this.acOn = Boolean(st.acOn);
      this.targetTempC = st.targetTempC;
      if (st.manualAc) {
        var gear = (typeof st.targetTempGear === 'number' && isFinite(st.targetTempGear))
          ? Math.max(1, Math.min(9, Math.round(st.targetTempGear)))
          : 3;
        this.targetTempText = 'Lvl ' + String(gear);
        this.acCircleLabel = this.acOn ? 'AC On' : ('AC L' + String(gear));
      } else {
        this.targetTempText = formatTemp(st.targetTempC, st.tempUnit);
        if (st.tempUnit === 'F') {
          var dispF = Math.round(st.targetTempC * 9 / 5 + 32);
          this.acCircleLabel = this.acOn ? 'AC On' : ('AC ' + String(dispF) + '°');
        } else {
          this.acCircleLabel = this.acOn ? 'AC On' : ('AC ' + String(st.targetTempC) + '°');
        }
      }
      this.acCircleBg = this.acOn ? '#0c3147' : '#131b24';
      this.acCircleBorder = this.acOn ? '#38bdf8' : '#223042';
      this.acCircleTextColor = this.acOn ? '#38bdf8' : '#e2e8f0';
      this.acPillBg = this.acOn ? '#38bdf8' : '#0e141b';
      this.acPillTextColor = this.acOn ? '#0a0e14' : '#38bdf8';
      this.acPillLabel = this.acOn ? 'Turn AC Off' : 'Start AC';
      this.acDurationLabel = 'Timer ' + String(normalizeAcDuration(st.acDurationMin)) + 'm';

      this.phoneOnline = Boolean(st.phoneOnline);
      this.hubBg = this.phoneOnline ? '#10b981' : '#f59e0b';
      this.phoneLinkText = this.phoneOnline ? 'Phone Connected' : 'Waiting for phone...';
      this.phoneLinkColor = this.phoneOnline ? '#10b981' : '#f59e0b';
      this.lastSyncText = formatSyncTime(st.ts);

      // Page 4 Comfort states
      var batOn = Boolean(st.batteryHeatOn);
      var batSupported = st.ts === null || Boolean(st.canBatteryHeat);
      this.batHeatLabel = batOn ? 'Bat On' : 'Bat Heat';
      this.batHeatBg = batOn ? '#2b1f09' : '#131b24';
      this.batHeatBorder = batOn ? '#f59e0b' : (batSupported ? '#223042' : '#1a222d');
      this.batHeatTextColor = batOn ? '#f59e0b' : (batSupported ? '#e2e8f0' : '#64748b');

      var seatLvl = typeof st.driverSeatLevel === 'number' ? st.driverSeatLevel : 0;
      var seatSupported = st.ts === null || Boolean(st.canSeatClimate);
      this.seatHeatLabel = seatLvl === 2 ? 'Seat Hi' : (seatLvl === 1 ? 'Seat Lo' : 'Seat Off');
      this.seatHeatBg = seatLvl > 0 ? '#2b1809' : '#131b24';
      this.seatHeatBorder = seatLvl > 0 ? '#f97316' : (seatSupported ? '#223042' : '#1a222d');
      this.seatHeatTextColor = seatLvl > 0 ? '#f97316' : (seatSupported ? '#e2e8f0' : '#64748b');

      // Page 5 Vehicle Health & TPMS
      var openCnt = (st.doorsOpenCount || 0) + (st.windowsOpenCount || 0) + (st.trunkOpen ? 1 : 0);
      if (openCnt === 0) {
        this.healthSummaryText = 'All Closed';
        this.healthSummaryColor = '#10b981';
        this.healthSummaryBorder = '#10b981';
        this.healthSummaryBg = '#0b2922';
      } else {
        this.healthSummaryText = String(st.doorsOpenCount || 0) + 'D ' + String(st.windowsOpenCount || 0) + 'W' + (st.trunkOpen ? ' +T' : '') + ' Open';
        this.healthSummaryColor = '#ef4444';
        this.healthSummaryBorder = '#ef4444';
        this.healthSummaryBg = '#291015';
      }
      this.tpmsLfText = formatPressure(st.tireLfKpa, st.pressureUnit);
      this.tpmsRfText = formatPressure(st.tireRfKpa, st.pressureUnit);
      this.tpmsLrText = formatPressure(st.tireLrKpa, st.pressureUnit);
      this.tpmsRrText = formatPressure(st.tireRrKpa, st.pressureUnit);
      var odoStr = (typeof st.totalMileageKm === 'number' && st.totalMileageKm > 0)
        ? ('Odo ' + formatDistance(st.totalMileageKm, st.distanceUnit))
        : 'Odo --';
      var outStr = (typeof st.outsideTempC === 'number' && isFinite(st.outsideTempC))
        ? (' · Out ' + formatTemp(st.outsideTempC, st.tempUnit))
        : '';
      this.healthFooterText = odoStr + outStr;

      // Page 6 My Garage slots (up to 3)
      var list = Array.isArray(st.vehicles) ? st.vehicles : [];
      this.hasMultipleCars = list.length > 1;
      if (list.length === 0) {
        this.car0Visible = true;
        this.car0Name = this.modelTitle;
        this.car0Sub = st.vin ? ('...' + st.vin.slice(-6)) : 'Active';
        this.car0Bg = '#0b2922';
        this.car0Border = '#10b981';
        this.car0Tag = 'ACTIVE';
        this.car1Visible = false;
        this.car2Visible = false;
      } else {
        this._bindCarSlot(0, list[0], st.vin);
        this._bindCarSlot(1, list[1], st.vin);
        this._bindCarSlot(2, list[2], st.vin);
      }

      this.confirmUnlock = Boolean(st.confirmUnlock);
      this.bannerText = st.banner || '';
      this.bannerBg = this.bannerIsError ? '#291015' : '#0e141b';
      this.bannerBorder = this.bannerIsError ? '#ef4444' : '#10b981';
      this.showBanner = Boolean(st.pending || (this.bannerText && this.bannerText.length > 0));
    },

    _bindCarSlot: function(idx, car, activeVin) {
      var visible = Boolean(car && car.vin);
      var isActive = visible && activeVin && car.vin === activeVin;
      if (idx === 0 && visible && !activeVin) {
        isActive = true;
      }
      var name = visible ? (car.name || car.model || 'EV') : '';
      var sub = visible ? ((car.plate ? car.plate + ' · ' : '') + '...' + car.vin.slice(-4)) : '';
      var bg = isActive ? '#0b2922' : '#131b24';
      var border = isActive ? '#10b981' : '#223042';
      var tag = isActive ? 'ACTIVE' : 'SWITCH';
      this['car' + idx + 'Visible'] = visible;
      this['car' + idx + 'Name'] = name;
      this['car' + idx + 'Sub'] = sub;
      this['car' + idx + 'Bg'] = bg;
      this['car' + idx + 'Border'] = border;
      this['car' + idx + 'Tag'] = tag;
    }
  };
}

function formatSyncTime(ts) {
  if (typeof ts !== 'number' || !isFinite(ts) || ts <= 0) {
    return 'Not synced';
  }
  var millis = ts > 1000000000000 ? ts : ts * 1000;
  var d = new Date(millis);
  var hh = ('0' + String(d.getHours())).slice(-2);
  var mm = ('0' + String(d.getMinutes())).slice(-2);
  return 'Synced ' + hh + ':' + mm;
}

export default createPageDefinition();
