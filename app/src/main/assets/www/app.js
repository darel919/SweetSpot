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
      profiles: []
    });

    const statusText = ref('Connecting…');
    const statusColor = ref('#6ee7a8');
    const editing = ref(false);
    const profileName = ref('');

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

    let timer = null;
    onMounted(() => {
      getState();
      timer = setInterval(getState, 1000);
    });
    onUnmounted(() => clearInterval(timer));

    return {
      state, statusText, statusColor, stateText, profileName,
      centerHz, onInput, onChange, applyBands,
      applyPreset, enable, bypass, showUi, hideUi,
      saveProfile, loadProfile, deleteProfile
    };
  }
}).mount('#app');
