const { createApp, reactive, ref, computed, onMounted, onUnmounted } = Vue;

createApp({
  setup() {
    const state = reactive({
      enabled: true,
      hasControl: false,
      activePreset: 1,
      presetName: 'Flat',
      ip: '',
      port: 8080,
      bands: [0, 0, 0, 0, 0],
      centerFrequenciesHz: [],
      bandLevelRange: [-1500, 1500],
      overlayVisible: false,
      presets: [{ id: 1, name: 'Flat' }, { id: 2, name: 'Night' }],
      profiles: [],
      calibration: { active: false, bands: [], frequenciesHz: [] }
    });

    const statusText = ref('Connecting…');
    const statusColor = ref('#6ee7a8');
    const editing = ref(false);
    const profileName = ref('');

    const probe = reactive({ running: false, available: false, highest: -1, recommended: -1, results: [] });
    const persistent = reactive({ active: false, bands: 0 });
    const curve = reactive({ name: null, summary: null });
    const device = reactive({
      pssTotalKb: 0, privateDirtyKb: 0, sharedDirtyKb: 0,
      javaHeapTotal: 0, javaHeapFree: 0, nativeHeapAllocated: 0,
      cpuPercent: 0, persistentProbeActive: false, persistentProbeBands: 0,
      audioserverCpuPercent: 0, audioserverPid: null
    });
    const persistBands = ref(64);
    const showCalWizard = ref(false);
    const calJson = ref('');
    const calStatus = ref('');
    const deviceText = computed(() => JSON.stringify(device, null, 2));

    const stateText = computed(() => JSON.stringify(state, null, 2));

    async function getState() {
      try {
        const r = await fetch('/api/state');
        const s = await r.json();
        // Don't overwrite values while the user is dragging a slider.
        if (!editing.value) {
          state.enabled = s.enabled;
          state.hasControl = s.hasControl;
          state.activePreset = s.activePreset;
          state.presetName = s.presetName;
          state.ip = s.ip;
          state.port = s.port;
          state.bands = s.bands;
          state.centerFrequenciesHz = s.centerFrequenciesHz;
          state.bandLevelRange = s.bandLevelRange;
          state.overlayVisible = s.overlayVisible;
          state.presets = s.presets;
          state.profiles = s.profiles;
        }
        state.calibration = s.calibration || { active: false, bands: [], frequenciesHz: [] };
        statusText.value = s.enabled ? 'Audio tuning active' : 'Bypassed';
        statusColor.value = s.enabled ? '#6ee7a8' : '#f0a868';
      } catch (e) {
        statusText.value = 'Offline';
        statusColor.value = '#f06a6a';
      }
    }

    function centerHz(i) {
      const hz = state.centerFrequenciesHz[i];
      return (hz != null) ? hz : ('Band ' + (i + 1));
    }

    function onInput(i, e) {
      editing.value = true;
      state.presetName = 'Custom';
      state.bands[i] = parseInt(e.target.value, 10);
    }

    async function onChange() {
      editing.value = false;
      await applyBands();
    }

    async function applyBands() {
      await fetch('/api/bands', {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({ levels: state.bands })
      });
      getState();
    }

    async function applyPreset(id) {
      await fetch('/api/preset', {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({ preset: id })
      });
      getState();
    }

    async function enable() {
      await fetch('/api/enable', { method: 'POST' });
      getState();
    }

    async function bypass() {
      await fetch('/api/bypass', { method: 'POST' });
      getState();
    }

    async function showUi() {
      await fetch('/api/showui', { method: 'POST' });
      getState();
    }

    async function hideUi() {
      await fetch('/api/hideui', { method: 'POST' });
      getState();
    }

    async function saveProfile() {
      const name = profileName.value.trim();
      if (!name) return;
      await fetch('/api/saveprofile', {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({ name })
      });
      profileName.value = '';
      getState();
    }

    async function loadProfile(name) {
      await fetch('/api/loadprofile', {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({ name })
      });
      getState();
    }

    async function deleteProfile(name) {
      await fetch('/api/deleteprofile', {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({ name })
      });
      getState();
    }

    async function runProbe() {
      await fetch('/api/probe', { method: 'POST' });
      pollProbe();
    }
    async function pollProbe() {
      try {
        const r = await fetch('/api/probe/status');
        const s = await r.json();
        probe.running = s.running;
        probe.available = s.available;
        probe.highest = s.highest;
        probe.recommended = s.recommended;
        probe.results = s.results || [];
        if (s.running) setTimeout(pollProbe, 1000);
      } catch (e) { /* offline */ }
    }
    async function refreshProbeStatus() {
      try {
        const r = await fetch('/api/probe/status');
        const s = await r.json();
        probe.running = s.running;
        probe.available = s.available;
        probe.highest = s.highest;
        probe.recommended = s.recommended;
        probe.results = s.results || [];
      } catch (e) { /* offline */ }
    }
    async function createPersistent(bands) {
      const b = bands || persistBands.value;
      await fetch('/api/probe/persist', {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({ bands: b })
      });
      refreshPersistent();
    }
    async function releasePersistent() {
      await fetch('/api/probe/release', { method: 'POST' });
      refreshPersistent();
    }
    async function refreshPersistent() {
      try {
        const r = await fetch('/api/probe/persistent');
        const s = await r.json();
        persistent.active = s.active;
        persistent.bands = s.bands;
        curve.name = s.curve || null;
        curve.summary = s.curveSummary || null;
      } catch (e) { /* offline */ }
    }
    async function applyCurve(c) {
      if (!persistent.active) {
        // No persistent instance yet — create one at the chosen band count, then apply.
        await createPersistent();
        for (let i = 0; i < 20; i++) {
          if (persistent.active) break;
          await new Promise(r => setTimeout(r, 500));
          await refreshPersistent();
        }
      }
      await fetch('/api/probe/apply-curve', {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({ curve: c })
      });
      refreshPersistent();
    }
    // One-click A/B: switch to band count [n] (releasing a different one first)
    // and apply the hollow curve so the audible effect can be compared directly.
    async function quickAudible(n) {
      if (persistent.active && persistent.bands !== n) {
        await releasePersistent();
        for (let i = 0; i < 10; i++) {
          if (!persistent.active) break;
          await new Promise(r => setTimeout(r, 300));
          await refreshPersistent();
        }
      }
      persistBands.value = n;
      await applyCurve('hollow');
    }
    function openCalibrationWizard() { showCalWizard.value = true; calStatus.value = ''; }
    function calibHeight(g) {
      const clamped = Math.max(-15, Math.min(15, g));
      return Math.round(20 + (clamped + 15) * 2); // 20..80 px
    }
    function applyCalibrationCurve() {
      let arr;
      try { arr = JSON.parse(calJson.value); } catch (e) { calStatus.value = 'Invalid JSON'; return; }
      if (!Array.isArray(arr) || arr.length !== 64) { calStatus.value = 'Need exactly 64 numbers'; return; }
      fetch('/api/eq/calibration', { method: 'POST', headers: { 'Content-Type': 'application/json' }, body: JSON.stringify({ gains: arr }) })
        .then(r => r.json()).then(d => { calStatus.value = d.error ? ('Failed: ' + d.error) : 'Applied'; getState(); })
        .catch(() => { calStatus.value = 'Request failed'; });
    }
    function loadTestCalibration() {
      const freqs = state.calibration.frequenciesHz;
      const gains = freqs.map(hz => (hz >= 300 && hz <= 3000) ? -15 : 0);
      fetch('/api/eq/calibration', { method: 'POST', headers: { 'Content-Type': 'application/json' }, body: JSON.stringify({ gains }) })
        .then(r => r.json()).then(() => { calStatus.value = 'Test curve applied'; getState(); });
    }
    function resetCalibration() {
      fetch('/api/eq/calibration/reset', { method: 'POST' })
        .then(() => { calStatus.value = 'Reset to flat'; getState(); });
    }
    async function refreshDevice() {
      try {
        const r = await fetch('/api/deviceinfo');
        const s = await r.json();
        device.pssTotalKb = s.pssTotalKb;
        device.privateDirtyKb = s.privateDirtyKb;
        device.sharedDirtyKb = s.sharedDirtyKb;
        device.javaHeapTotal = s.javaHeapTotal;
        device.javaHeapFree = s.javaHeapFree;
        device.nativeHeapAllocated = s.nativeHeapAllocated;
        device.cpuPercent = s.cpuPercent;
        device.persistentProbeActive = s.persistentProbeActive;
        device.persistentProbeBands = s.persistentProbeBands;
        device.audioserverCpuPercent = s.audioserverCpuPercent;
        device.audioserverPid = s.audioserverPid;
      } catch (e) { /* offline */ }
    }

    let timer = null;
    let deviceTimer = null;
    onMounted(() => {
      getState();
      refreshProbeStatus();
      refreshPersistent();
      refreshDevice();
      timer = setInterval(() => { getState(); refreshProbeStatus(); refreshPersistent(); }, 1000);
      deviceTimer = setInterval(refreshDevice, 2000);
    });
    onUnmounted(() => { clearInterval(timer); clearInterval(deviceTimer); });

    return {
      state, statusText, statusColor, stateText, profileName,
      centerHz, onInput, onChange, applyBands,
      applyPreset, enable, bypass, showUi, hideUi,
      saveProfile, loadProfile, deleteProfile,
      probe, persistent, deviceText, persistBands, curve, device,
      runProbe, createPersistent, releasePersistent, applyCurve, quickAudible,
      showCalWizard, calJson, calStatus,
      openCalibrationWizard, calibHeight, applyCalibrationCurve, loadTestCalibration, resetCalibration
    };
  }
}).mount('#app');
