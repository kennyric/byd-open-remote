import { describe, it } from 'node:test';
import assert from 'node:assert/strict';
import fs from 'node:fs';
import path from 'node:path';

import {
  createInitialState,
  formatDistance,
  formatPressure,
  formatTemp
} from '../entry/src/main/js/default/common/carState.js';
import {
  COMPANION_PKG,
  COMPANION_URL,
  DISCLAIMER_STORAGE_KEY,
  createPageDefinition
} from '../entry/src/main/js/default/pages/index/index.js';

function createFakeP2pClient() {
  return {
    receiver: null,
    sentPayloads: [],
    setPeerPkgName(pkg) { this.pkg = pkg; },
    setPeerFingerPrint(fp) { this.fp = fp; },
    registerReceiver(receiver) {
      this.receiver = receiver;
      if (receiver && receiver.onSuccess) receiver.onSuccess({ code: 0 });
    },
    unregisterReceiver() { this.receiver = null; },
    send(msg, cb) {
      var raw = typeof msg === 'string' ? msg : (msg && msg.data ? msg.data : '');
      this.sentPayloads.push(JSON.parse(raw));
      if (cb && cb.onSuccess) cb.onSuccess({ code: 0 });
    }
  };
}

describe('EV Watch Link (AppGallery Store Edition)', () => {
  it('uses copyright-free defaults and assets without automaker trademarks', () => {
    const root = path.resolve(path.dirname(new URL(import.meta.url).pathname.replace(/^\/([A-Za-z]:)/, '$1')), '..');
    const cfg = fs.readFileSync(path.join(root, 'entry/src/main/config.json'), 'utf8');
    const strs = fs.readFileSync(path.join(root, 'entry/src/main/resources/base/element/string.json'), 'utf8');
    const hml = fs.readFileSync(path.join(root, 'entry/src/main/js/default/pages/index/index.hml'), 'utf8');

    assert.ok(cfg.includes('"com.evwatch.bridge"'));
    assert.ok(!/byd|atto/i.test(cfg));
    assert.ok(!/byd|atto/i.test(strs));
    assert.ok(!/byd|atto/i.test(hml));
    assert.ok(hml.includes('qr_apk.png'));
    assert.ok(hml.includes('ev_emblem.png'));

    const init = createInitialState();
    assert.equal(init.modelName, 'EV REMOTE');
    assert.equal(COMPANION_URL, 'https://github.com');
  });

  it('blocks watch commands behind first-launch disclaimer gate until accepted and persisted', () => {
    var client = createFakeP2pClient();
    var store = {};
    var fakeStorage = {
      get({ key, success }) { if (success && store[key]) success(store[key]); },
      set({ key, value }) { store[key] = value; }
    };

    var page = createPageDefinition({ getP2pClient: () => client, systemStorage: fakeStorage });
    page.onInit();
    page.onShow();

    // Gate must block status & unlock before agreement
    assert.equal(page.showDisclaimer, true);
    assert.equal(page.disclaimerAccepted, false);
    assert.equal(client.sentPayloads.length, 0);

    page.onPrimaryLockTap();
    assert.equal(page.confirmUnlock, false);
    assert.equal(client.sentPayloads.length, 0);

    // Accepting persists key and immediately sends status sync
    page.acceptDisclaimer();
    assert.equal(page.showDisclaimer, false);
    assert.equal(page.disclaimerAccepted, true);
    assert.equal(store[DISCLAIMER_STORAGE_KEY], 'true');
    assert.equal(client.sentPayloads.length, 1);
    assert.equal(client.sentPayloads[0].action, 'status');

    // Re-launching reads persisted key and skips gate
    var client2 = createFakeP2pClient();
    var page2 = createPageDefinition({ getP2pClient: () => client2, systemStorage: fakeStorage });
    page2.onInit();
    assert.equal(page2.disclaimerAccepted, true);
    assert.equal(page2.showDisclaimer, false);
  });

  it('supports units, TPMS, comfort commands, and disclaimer re-open from QR page', () => {
    var client = createFakeP2pClient();
    var page = createPageDefinition({ getP2pClient: () => client, disclaimerAccepted: true, systemStorage: null });
    page.onInit();
    page.onShow();

    assert.equal(formatDistance(100, 'mi'), '62 mi');
    assert.equal(formatTemp(20, 'F'), '68°F');
    assert.equal(formatPressure(250, 'bar'), '2.50 bar');

    client.receiver.onReceiveMessage(JSON.stringify({
      id: client.sentPayloads[0].id,
      ok: true,
      action: 'status',
      state: {
        soc: 90,
        rangeKm: 410,
        tpmsLf: 250,
        uPres: 'bar',
        uDist: 'km'
      }
    }));

    assert.equal(page.tpmsLfText, '2.50 bar');
    assert.equal(page.modelTitle, 'EV REMOTE');
    assert.equal(page.confirmTitle, 'Unlock Vehicle?');

    page.openDisclaimer();
    assert.equal(page.showDisclaimer, true);
  });

  it('handles manual AC gears and debounced live ac_on updates when temp changes while AC is on', async () => {
    var client = createFakeP2pClient();
    var page = createPageDefinition({ getP2pClient: () => client, disclaimerAccepted: true, systemStorage: null });
    page.onInit();
    page.onShow();

    assert.equal(client.sentPayloads.length, 1);
    assert.equal(client.sentPayloads[0].action, 'status');

    client.receiver.onReceiveMessage(JSON.stringify({
      id: client.sentPayloads[0].id,
      ok: true,
      action: 'status',
      state: {
        soc: 80,
        rangeKm: 240,
        acOn: true,
        manAc: true,
        tempGear: 3
      }
    }));

    assert.equal(page.targetTempText, 'Lvl 3');
    page.onTempPlusTap();
    page.onTempPlusTap();
    assert.equal(page.targetTempText, 'Lvl 5');

    await new Promise((resolve) => setTimeout(resolve, 800));

    assert.equal(client.sentPayloads.length, 2);
    assert.equal(client.sentPayloads[1].action, 'ac_on');
    assert.equal(client.sentPayloads[1].tempGear, 5);
    page.onDestroy();
  });

  it('targets com.bydopenremote.app, cycles AC timer, and parses VehicleStatus.toJson fields', async () => {
    assert.equal(COMPANION_PKG, 'com.bydopenremote.app');
    var client = createFakeP2pClient();
    var page = createPageDefinition({ getP2pClient: () => client, disclaimerAccepted: true, systemStorage: null });
    page.onInit();
    assert.equal(client.pkg, 'com.bydopenremote.app');
    page.onShow();

    client.receiver.onReceiveMessage(JSON.stringify({
      id: client.sentPayloads[0].id,
      ok: true,
      action: 'status',
      state: {
        vin: 'VIN_EV1',
        modelName: 'EV SEDAN',
        soc: 64,
        rangeKm: 280,
        locked: false,
        carOn: true,
        acOn: true,
        charging: 1,
        chgKw: 11.0,
        chgMin: 45,
        openDoors: 1,
        openWins: 1,
        tpms: [245, 244, 240, 241],
        batHeat: true,
        seat: 1,
        ts: 1700000900,
        cars: [
          { vin: 'VIN_EV1', name: 'EV SEDAN' },
          { vin: 'VIN_EV2', name: 'EV SUV' }
        ]
      }
    }));

    assert.equal(page.lockText, 'Unlocked · On');
    assert.equal(page.rangeText, '280 km range · 11.0kW 45m');
    assert.equal(page.tpmsLfText, '245 kPa');
    assert.equal(page.car1Name, 'EV SUV');
    assert.equal(page.acDurationLabel, 'Timer 20m');

    page.onAcDurationTap();
    assert.equal(page.acDurationLabel, 'Timer 25m');
    await new Promise((resolve) => setTimeout(resolve, 800));
    assert.equal(client.sentPayloads.length, 2);
    assert.equal(client.sentPayloads[1].action, 'ac_on');
    assert.equal(client.sentPayloads[1].acDurationMin, 25);

    page.onDestroy();
  });
});
