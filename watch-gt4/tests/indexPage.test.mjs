import { describe, it } from 'node:test';
import assert from 'node:assert/strict';

import {
  COMPANION_PKG,
  COMPANION_FINGERPRINT,
  COMMAND_TIMEOUT_MS,
  CLIMATE_DEBOUNCE_MS,
  createPageDefinition
} from '../entry/src/main/js/default/pages/index/index.js';

function createFakeP2pClient() {
  return {
    pkgName: null,
    fingerPrint: null,
    receiver: null,
    unregistered: false,
    sentPayloads: [],
    nextSendShouldFail: false,
    setPeerPkgName(pkg) {
      this.pkgName = pkg;
    },
    setPeerFingerPrint(fp) {
      this.fingerPrint = fp;
    },
    registerReceiver(receiver) {
      this.receiver = receiver;
      if (receiver && receiver.onSuccess) {
        receiver.onSuccess({ code: 0 });
      }
    },
    unregisterReceiver() {
      this.unregistered = true;
      this.receiver = null;
    },
    send(msg, cb) {
      var raw = typeof msg === 'string' ? msg : (msg && msg.data ? msg.data : '');
      this.sentPayloads.push(JSON.parse(raw));
      if (this.nextSendShouldFail) {
        if (cb && cb.onFailure) cb.onFailure(-1, 'offline');
        return;
      }
      if (cb && cb.onSuccess) cb.onSuccess({ code: 0 });
    }
  };
}

describe('Watch GT 4 index page controller', () => {
  it('initializes Wear Engine peer info and requests status on show', () => {
    var client = createFakeP2pClient();
    var page = createPageDefinition({ getP2pClient: () => client });

    page.onInit();
    assert.equal(client.pkgName, COMPANION_PKG);
    assert.equal(client.fingerPrint, COMPANION_FINGERPRINT);
    assert.ok(client.receiver);

    page.onShow();
    assert.equal(client.sentPayloads.length, 1);
    assert.equal(client.sentPayloads[0].action, 'status');
    assert.equal(page.showBanner, true);
    assert.equal(page.bannerText, 'Syncing...');

    // Simulate status reply from phone (without cabinTempC)
    client.receiver.onReceiveMessage(JSON.stringify({
      id: client.sentPayloads[0].id,
      ok: true,
      action: 'status',
      state: { soc: 82, rangeKm: 310, locked: true, acOn: false, cabinTempC: null, targetTempC: 19, ts: 1700000000 }
    }));

    assert.equal(page.socPercent, 82);
    assert.equal(page.socText, '82%');
    assert.equal(page.rangeText, '310 km range');
    assert.equal(page.locked, true);
    assert.equal(page.hasCabinTemp, false);
    assert.equal(page.cabinTempText, '');
    assert.equal(page.targetTempC, 19);
    assert.equal(page.acCircleLabel, 'AC 19°');
    assert.equal(page.phoneOnline, true);

    page.onDestroy();
    assert.equal(client.unregistered, true);
  });

  it('requires 2-tap confirmation to unlock and sends lock directly when unlocked', () => {
    var client = createFakeP2pClient();
    var page = createPageDefinition({ getP2pClient: () => client });
    page.onInit();

    // Locked initially -> tap opens confirmation without sending
    page.onPrimaryLockTap();
    assert.equal(page.confirmUnlock, true);
    assert.equal(client.sentPayloads.length, 0);

    // Cancel closes confirmation cleanly
    page.cancelUnlockAction();
    assert.equal(page.confirmUnlock, false);
    assert.equal(client.sentPayloads.length, 0);

    // Confirm dispatches unlock
    page.onPrimaryLockTap();
    page.confirmUnlockAction();
    assert.equal(page.confirmUnlock, false);
    assert.equal(client.sentPayloads.length, 1);
    assert.equal(client.sentPayloads[0].action, 'unlock');

    client.receiver.onReceiveMessage(JSON.stringify({
      id: client.sentPayloads[0].id,
      ok: true,
      action: 'unlock',
      state: { soc: 80, rangeKm: 300, locked: false, acOn: false, cabinTempC: 27, targetTempC: 17, ts: 1700000050 }
    }));

    assert.equal(page.locked, false);
    assert.equal(page.hasCabinTemp, true);
    assert.equal(page.cabinTempText, '27°C');
    assert.equal(page.bannerText, 'Car Unlocked');
    assert.equal(page.bannerIsError, false);

    // Now unlocked -> primary tap sends lock directly
    page.onPrimaryLockTap();
    assert.equal(page.confirmUnlock, false);
    assert.equal(client.sentPayloads[1].action, 'lock');

    page.onDestroy();
  });

  it('handles climate stepper, diamond actions, error banners, and command timeouts', (t) => {
    t.mock.timers.enable({ apis: ['setTimeout'] });
    var client = createFakeP2pClient();
    var page = createPageDefinition({ getP2pClient: () => client });
    page.onInit();

    // Step temp bounds 17..31 and start AC
    page.onTempMinusTap();
    assert.equal(page.targetTempC, 17);
    page.onTempPlusTap();
    page.onTempPlusTap();
    assert.equal(page.targetTempC, 19);

    page.onAcToggleTap();
    assert.equal(client.sentPayloads[0].action, 'ac_on');
    assert.equal(client.sentPayloads[0].tempC, 19);

    // Simulate invalid PIN error from phone
    client.receiver.onReceiveMessage(JSON.stringify({
      id: client.sentPayloads[0].id,
      ok: false,
      action: 'ac_on',
      err: 'PIN_INVALID',
      msg: 'Invalid PIN'
    }));
    assert.equal(page.bannerText, 'Invalid PIN');
    assert.equal(page.bannerIsError, true);

    // Trigger flash lights and let 15s timeout expire
    page.onLightsTap();
    assert.equal(client.sentPayloads[1].action, 'lights');
    t.mock.timers.tick(COMMAND_TIMEOUT_MS + 10);
    assert.equal(page.bannerText, 'Request Timeout');
    assert.equal(page.bannerIsError, true);
    assert.equal(page.phoneOnline, false);

    // Offline send failure shows Phone Offline
    client.nextSendShouldFail = true;
    page.onHornTap();
    assert.equal(page.bannerText, 'Phone Offline');
    assert.equal(page.bannerIsError, true);

    page.onDestroy();
  });

  it('exits on edge-back swipe from main page only and toggles swipe-to-dismiss per page', () => {
    var client = createFakeP2pClient();
    var terminated = 0;
    var dismissStates = [];
    var fakeApp = {
      terminate() { terminated += 1; },
      setSwipeToDismiss(enabled) { dismissStates.push(enabled); }
    };
    var page = createPageDefinition({
      getP2pClient: () => client,
      systemApp: fakeApp
    });

    page.onInit();
    page.onShow();
    assert.deepEqual(dismissStates, [true]);

    // Swiping forward to page 2 disables swipe-to-dismiss; swiping back from page 2 does not exit
    page.onSwiperChange({ index: 1 });
    assert.deepEqual(dismissStates, [true, false]);

    page.onMainTouchStart({ touches: [{ globalX: 60, globalY: 200 }] });
    page.onMainTouchEnd({ changedTouches: [{ globalX: 280, globalY: 205 }] });
    page.onMainSwipe({ direction: 'right' });
    assert.equal(terminated, 0);

    // Return to page 0 re-enables swipe-to-dismiss
    page.onSwiperChange({ index: 0 });
    assert.deepEqual(dismissStates, [true, false, true]);

    // Swiping left-to-right off page 0 terminates the app
    page.onMainTouchStart({ touches: [{ globalX: 30, globalY: 220 }] });
    page.onMainTouchEnd({ changedTouches: [{ globalX: 190, globalY: 228 }] });
    assert.equal(terminated, 1);

    // onMainSwipe('right') on page 0 also exits when touchstart didn't pre-capture
    page.onMainSwipe({ direction: 'right' });
    assert.equal(terminated, 2);

    // Forward swipe ('left') to page 2 must NOT terminate
    page.onMainSwipe({ direction: 'left' });
    assert.equal(terminated, 2);

    // Clear initial pending status before opening unlock confirmation modal
    client.receiver.onReceiveMessage(JSON.stringify({
      id: client.sentPayloads[0].id,
      ok: true,
      action: 'status',
      state: { soc: 80, rangeKm: 300, locked: true, acOn: false, cabinTempC: 25, targetTempC: 17, ts: 1700000000 }
    }));

    // While unlock confirmation modal is open, swipe must not exit
    page.onPrimaryLockTap();
    assert.equal(page.confirmUnlock, true);
    page.onMainSwipe({ direction: 'right' });
    assert.equal(terminated, 2);

    page.onDestroy();
    assert.equal(dismissStates[dismissStates.length - 1], false);
  });

  it('supports comfort actions, TPMS/health units, and multi-car switching on pages 4-6', () => {
    var client = createFakeP2pClient();
    var page = createPageDefinition({ getP2pClient: () => client });
    page.onInit();
    page.onShow();

    // Feed multi-car, charging, TPMS, PSI/mi/F telemetry
    client.receiver.onReceiveMessage(JSON.stringify({
      id: client.sentPayloads[0].id,
      ok: true,
      action: 'status',
      state: {
        vin: 'VIN_A',
        modelName: 'SEAL U',
        soc: 74,
        rangeKm: 342,
        chargingState: 1,
        chgMin: 40,
        doorsOpen: 0,
        winsOpen: 0,
        trunkOpen: false,
        odoKm: 12000,
        outTempC: 25,
        tpmsLf: 250,
        tpmsRf: 248,
        tpmsLr: 240,
        tpmsRr: 242,
        batHeatOn: true,
        seatLvl: 2,
        uDist: 'mi',
        uTemp: 'F',
        uPres: 'PSI',
        cars: [
          { vin: 'VIN_A', model: 'SEAL U', plate: 'B1' },
          { vin: 'VIN_B', model: 'ATTO 1', plate: 'B2' }
        ]
      }
    }));

    assert.equal(page.modelTitle, 'SEAL U');
    assert.equal(page.confirmTitle, 'Unlock SEAL U?');
    assert.equal(page.rangeText, '213 mi range · Chg 40m');
    assert.equal(page.batHeatLabel, 'Bat On');
    assert.equal(page.seatHeatLabel, 'Seat Hi');
    assert.equal(page.healthSummaryText, 'All Closed');
    assert.equal(page.tpmsLfText, '36.3 PSI');
    assert.equal(page.hasMultipleCars, true);
    assert.equal(page.car0Tag, 'ACTIVE');
    assert.equal(page.car1Name, 'ATTO 1');
    assert.equal(page.car1Tag, 'SWITCH');

    // Comfort tap: switch car 1
    page.onSelectCar1();
    assert.equal(client.sentPayloads[1].action, 'switch_car');
    assert.equal(client.sentPayloads[1].vin, 'VIN_B');

    // Clear pending and test vent_windows
    client.receiver.onReceiveMessage(JSON.stringify({
      id: client.sentPayloads[1].id,
      ok: true,
      action: 'switch_car',
      state: { vin: 'VIN_B', modelName: 'ATTO 1' }
    }));
    page.onVentWindowsTap();
    assert.equal(client.sentPayloads[2].action, 'vent_windows');

    page.onDestroy();
  });

  it('sends debounced ac_on when temp changes while AC is on and handles manAc gears', (t) => {
    t.mock.timers.enable({ apis: ['setTimeout'] });
    var client = createFakeP2pClient();
    var page = createPageDefinition({ getP2pClient: () => client });
    page.onInit();
    page.onShow();

    client.receiver.onReceiveMessage(JSON.stringify({
      id: client.sentPayloads[0].id,
      ok: true,
      action: 'status',
      state: { soc: 79, rangeKm: 237, locked: true, acOn: true, manAc: true, targetTempC: 17, ts: 1700000100 }
    }));

    assert.equal(page.targetTempText, 'Lvl 3');
    page.onTempPlusTap();
    page.onTempPlusTap();
    assert.equal(page.targetTempText, 'Lvl 5');
    assert.equal(client.sentPayloads.length, 1);

    t.mock.timers.tick(CLIMATE_DEBOUNCE_MS + 10);
    assert.equal(client.sentPayloads.length, 2);
    assert.equal(client.sentPayloads[1].action, 'ac_on');
    assert.equal(client.sentPayloads[1].tempGear, 5);

    page.onDestroy();
  });

  it('targets com.bydopenremote.app, cycles AC timer, and renders VehicleStatus.toJson fields', (t) => {
    t.mock.timers.enable({ apis: ['setTimeout'] });
    assert.equal(COMPANION_PKG, 'com.bydopenremote.app');

    var client = createFakeP2pClient();
    var page = createPageDefinition({ getP2pClient: () => client });
    page.onInit();
    assert.equal(client.pkgName, 'com.bydopenremote.app');
    page.onShow();

    client.receiver.onReceiveMessage(JSON.stringify({
      id: client.sentPayloads[0].id,
      ok: true,
      action: 'status',
      state: {
        vin: 'VIN_NEW',
        modelName: 'SEAL',
        soc: 76,
        rangeKm: 300,
        locked: false,
        carOn: true,
        acOn: true,
        charging: 1,
        chgKw: 7.4,
        chgMin: 25,
        openDoors: 1,
        openWins: 0,
        tpms: [252, 250, 248, 246],
        batHeat: false,
        seat: 0,
        capBatHeat: false,
        capSeat: true,
        ts: 1700000500,
        cars: [
          { vin: 'VIN_NEW', name: 'SEAL' },
          { vin: 'VIN_ALT', name: 'ATTO 3' }
        ]
      }
    }));

    assert.equal(page.lockText, 'Unlocked · On');
    assert.equal(page.rangeText, '300 km range · 7.4kW 25m');
    assert.equal(page.tpmsLfText, '252 kPa');
    assert.equal(page.car0Name, 'SEAL');
    assert.equal(page.car1Name, 'ATTO 3');
    assert.equal(page.batHeatTextColor, '#64748b');
    assert.equal(page.seatHeatTextColor, '#e2e8f0');
    assert.equal(page.acDurationLabel, 'Timer 20m');

    page.onAcDurationTap();
    assert.equal(page.acDurationLabel, 'Timer 25m');
    t.mock.timers.tick(CLIMATE_DEBOUNCE_MS + 10);
    assert.equal(client.sentPayloads.length, 2);
    assert.equal(client.sentPayloads[1].action, 'ac_on');
    assert.equal(client.sentPayloads[1].acDurationMin, 25);

    page.onDestroy();
  });
});
