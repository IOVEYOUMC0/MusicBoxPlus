const DEFAULT_MAX_PITCH = 24;
const TICKS_PER_VIEW = 64;
const TICK_WIDTH = 32;
const NOTE_ROW_HEIGHT = 32;
const RULER_HEIGHT = 26;
const DRAG_EDGE_SCROLL_ZONE = 48;
const DRAG_EDGE_SCROLL_MAX_TICKS = 4;
const DRAG_EDGE_SCROLL_MAX_ROWS = 3;
const DRAG_EDGE_SCROLL_INTERVAL = 80;
const TIMELINE_PADDING_TICKS = TICKS_PER_VIEW * 2;
const MIDI_KEY_FOR_EDITOR_PITCH_ZERO = 54;
const AUTO_SAVE_INTERVAL = 30000;
const DRAFT_SAVE_DELAY = 600;
const LOCAL_STORAGE_PREFIX = 'musicbox_editor_draft_';
const SESSION_EXPIRE_WARNING_MINUTES = 2;
const MAX_UNDO_STATES = 80;

const NOTE_NAMES = ['C', 'C#', 'D', 'D#', 'E', 'F', 'F#', 'G', 'G#', 'A', 'A#', 'B'];
const TIME_SIGNATURES = ['1/4', '2/4', '3/4', '4/4', '5/4', '7/4', '3/8', '6/8', '9/8', '12/8'];
const DEFAULT_INSTRUMENT_MATERIALS = {
    HARP: 'note_block',
    BASS: 'oak_planks',
    BASS_DRUM: 'stone',
    SNARE_DRUM: 'sand',
    CLICKS: 'glass',
    GUITAR: 'oak_fence',
    FLUTE: 'clay',
    BELL: 'gold_block',
    CHIME: 'packed_ice',
    XYLOPHONE: 'bone_block',
    IRON_XYLOPHONE: 'iron_block',
    COW_BELL: 'soul_sand',
    DIDGERIDOO: 'pumpkin',
    BIT: 'emerald_block',
    BANJO: 'hay_block',
    PLING: 'glowstone',
    TRUMPET: 'copper_block',
    TRUMPET_EXPOSED: 'exposed_copper',
    TRUMPET_WEATHERED: 'weathered_copper',
    TRUMPET_OXIDIZED: 'oxidized_copper'
};
const STANDARD_NBS_INSTRUMENTS = [
    { id: 'HARP', label: 'Harp', material: 'note_block', sound: 'minecraft:block.note_block.harp' },
    { id: 'BASS', label: 'Bass', material: 'oak_planks', sound: 'minecraft:block.note_block.bass' },
    { id: 'BASS_DRUM', label: 'Bass Drum', material: 'stone', sound: 'minecraft:block.note_block.basedrum' },
    { id: 'SNARE_DRUM', label: 'Snare', material: 'sand', sound: 'minecraft:block.note_block.snare' },
    { id: 'CLICKS', label: 'Hat', material: 'glass', sound: 'minecraft:block.note_block.hat' },
    { id: 'GUITAR', label: 'Guitar', material: 'oak_fence', sound: 'minecraft:block.note_block.guitar' },
    { id: 'FLUTE', label: 'Flute', material: 'clay', sound: 'minecraft:block.note_block.flute' },
    { id: 'BELL', label: 'Bell', material: 'gold_block', sound: 'minecraft:block.note_block.bell' },
    { id: 'CHIME', label: 'Chime', material: 'packed_ice', sound: 'minecraft:block.note_block.chime' },
    { id: 'XYLOPHONE', label: 'Xylophone', material: 'bone_block', sound: 'minecraft:block.note_block.xylophone' },
    { id: 'IRON_XYLOPHONE', label: 'Iron Xylophone', material: 'iron_block', sound: 'minecraft:block.note_block.iron_xylophone' },
    { id: 'COW_BELL', label: 'Cow Bell', material: 'soul_sand', sound: 'minecraft:block.note_block.cow_bell' },
    { id: 'DIDGERIDOO', label: 'Didgeridoo', material: 'pumpkin', sound: 'minecraft:block.note_block.didgeridoo' },
    { id: 'BIT', label: 'Bit', material: 'emerald_block', sound: 'minecraft:block.note_block.bit' },
    { id: 'BANJO', label: 'Banjo', material: 'hay_block', sound: 'minecraft:block.note_block.banjo' },
    { id: 'PLING', label: 'Pling', material: 'glowstone', sound: 'minecraft:block.note_block.pling' }
];
const NBS_INSTRUMENT_ASSETS = {
    HARP: 'harp',
    PIANO: 'harp',
    BASS: 'bass',
    DOUBLE_BASS: 'bass',
    BASS_DRUM: 'bass-drum',
    BASEDRUM: 'bass-drum',
    SNARE: 'snare-drum',
    SNARE_DRUM: 'snare-drum',
    CLICKS: 'click',
    CLICK: 'click',
    HAT: 'click',
    HI_HAT: 'click',
    GUITAR: 'guitar',
    FLUTE: 'flute',
    BELL: 'bell',
    CHIME: 'chime',
    XYLOPHONE: 'xylophone',
    IRON_XYLOPHONE: 'iron-xylophone',
    IRON_XYLO: 'iron-xylophone',
    COW_BELL: 'cow-bell',
    COWBELL: 'cow-bell',
    DIDGERIDOO: 'didgeridoo',
    BIT: 'bit',
    BANJO: 'banjo',
    PLING: 'pling',
    TRUMPET: 'custom',
    TRUMPET_EXPOSED: 'custom',
    TRUMPET_WEATHERED: 'custom',
    TRUMPET_OXIDIZED: 'custom'
};

class MusicEditor {
    constructor() {
        this.sessionId = MusicEditor.getSessionId();
        this.csrfToken = null;
        this.audioContext = null;
        this.instruments = [];
        this.instrumentMap = new Map();
        this.instrumentIds = new Set();
        this.selectedInstrument = 'HARP';
        this.selectedNotes = [];
        this.clipboard = [];
        this.isPlaying = false;
        this.currentTick = 0;
        this.playStartTick = 0;
        this.playEndTick = 0;
        this.playInterval = null;
        this.lastHighlightedTick = null;
        this.volume = 0.7;
        this.playbackSpeed = 1.0;
        this.lastBeatTick = null;
        // Mutable grid metrics, kept in sync with the --tick-width / --note-row-height CSS vars
        // so Ctrl+wheel zoom re-renders without recompiling the layout constants.
        this.tickWidth = TICK_WIDTH;
        this.noteRowHeight = NOTE_ROW_HEIGHT;
        this.zoomAccumulator = null;
        this.dragAction = null;
        this.dragTouched = new Set();
        this.dragPointerId = null;
        this.dragCaptureTarget = null;
        this.moveDrag = null;
        this.edgeScrollFrame = null;
        this.edgeScrollPointer = null;
        this.edgeScrollDelta = { tick: 0, row: 0 };
        this.edgeScrollLastAt = 0;
        this.suppressNextClick = false;
        this.lastSelectedNote = null;
        this.musicData = null;
        this.undoStack = [];
        this.redoStack = [];
        this.autoSaveTimer = null;
        this.draftSaveTimer = null;
        this.visibleTickStart = 0;
        this.sessionExpireTime = null;
        this.sessionCheckInterval = null;
        this.sessionExpired = false;
        this.sessionExpiredDialog = null;
        this.noteIndex = new Map();
        this.notesByTick = new Map();
        this.dirty = false;
        this.saveInFlight = false;
        this.saveQueued = false;
        this.changeVersion = 0;
        this.lastSavedAt = null;
        this.maxPitch = DEFAULT_MAX_PITCH;
        this.configuredMaxPitch = DEFAULT_MAX_PITCH;
        this.maxTickLimit = 10000;
        this.maxTicks = 10000;
        this.settings = {
            maxTicks: 10000,
            minBpm: 20,
            maxBpm: 300,
            defaultMaxPitch: DEFAULT_MAX_PITCH,
            extendedMaxPitch: 119,
            enable10octave: false,
            defaultBeatSubdivision: 4,
            timeSignatures: TIME_SIGNATURES
        };
    }

    static getSessionId() {
        const params = new URLSearchParams(window.location.search);
        return params.get('session');
    }

    async init() {
        if (!this.sessionId) {
            this.showStatus('缺少编辑会话');
            return;
        }

        this.musicData = this.createEmptyMusicData();
        this.renderEditor();
        this.updateHeader();

        await this.validateSession();
        if (this.sessionExpired) return;
        await Promise.all([
            this.loadSettings(),
            this.loadInstruments()
        ]);
        if (this.sessionExpired) return;
        await this.loadMusic();
        if (this.sessionExpired) return;

        this.setupEventListeners();
        this.loadFromLocalStorage();
        this.startAutoSave();
        this.startSessionCheck();
    }

    createEmptyMusicData() {
        return {
            id: null,
            title: 'Loading...',
            tempo: 120,
            beatSubdivision: this.settings.defaultBeatSubdivision,
            timeSignature: '4/4',
            notes: [],
            maxTicks: this.settings.maxTicks
        };
    }

    async validateSession() {
        if (this.sessionExpired) return;
        try {
            const response = await fetch(`/api/session?session=${encodeURIComponent(this.sessionId)}`);
            if (!response.ok) {
                if (response.status === 401) {
                    this.showSessionExpiredDialog();
                }
                return;
            }

            const data = await response.json();
            if (data.expiresAt) {
                this.sessionExpireTime = data.expiresAt;
            }
            if (data.csrfToken) {
                this.csrfToken = data.csrfToken;
            }
        } catch (error) {
            console.error('Session validation failed:', error);
            this.showStatus('会话校验失败');
        }
    }

    apiFetch(url, options = {}) {
        if (this.sessionExpired) {
            return Promise.reject(new Error('编辑会话已过期'));
        }
        const target = new URL(url, window.location.origin);
        target.searchParams.set('session', this.sessionId);

        const headers = { ...(options.headers || {}) };
        if (this.csrfToken) {
            headers['X-CSRF-Token'] = this.csrfToken;
        }

        return fetch(`${target.pathname}${target.search}`, { ...options, headers });
    }

    handleApiUnauthorized(response) {
        if (response?.status !== 401) return false;
        this.showSessionExpiredDialog();
        return true;
    }

    startSessionCheck() {
        if (this.sessionExpired) return;
        this.sessionCheckInterval = setInterval(() => {
            this.checkSessionExpiry();
        }, 60000);
    }

    checkSessionExpiry() {
        if (this.sessionExpired || !this.sessionExpireTime) return;

        const now = Math.floor(Date.now() / 1000);
        const remainingMinutes = Math.floor((this.sessionExpireTime - now) / 60);

        if (remainingMinutes <= SESSION_EXPIRE_WARNING_MINUTES && remainingMinutes > 0) {
            this.showStatus(`会话将在 ${remainingMinutes} 分钟后过期`);
        } else if (remainingMinutes <= 0) {
            this.showSessionExpiredDialog();
        }
    }

    showSessionExpiredDialog() {
        if (this.sessionExpired || this.sessionExpiredDialog) return;
        this.sessionExpired = true;
        this.saveToLocalStorage(true);
        this.stopMusic();
        this.stopAutoSave();
        if (this.sessionCheckInterval) {
            clearInterval(this.sessionCheckInterval);
            this.sessionCheckInterval = null;
        }
        this.closeImportDialog();
        this.disableEditorForExpiredSession();
        this.sessionExpiredDialog = this.showDialog({
            className: 'session-expired-dialog',
            title: '会话已过期',
            lines: ['当前草稿已保存到浏览器本地存储。', '请关闭此页面，并在游戏内重新获取编辑链接。'],
            buttons: [
                { id: 'close', label: '关闭页面', primary: true, onClick: () => this.closeExpiredPage() }
            ]
        });
    }

    disableEditorForExpiredSession() {
        document.body.classList.add('session-expired');
        document.querySelectorAll('button, input, select, textarea').forEach(element => {
            if (element.closest('.session-expired-dialog')) return;
            element.disabled = true;
        });
        this.showStatus('会话已过期，请重新获取编辑链接');
    }

    closeExpiredPage() {
        window.close();
        document.body.classList.add('session-expired-closed');
        if (this.sessionExpiredDialog) {
            this.sessionExpiredDialog.remove();
            this.sessionExpiredDialog = null;
        }
        document.body.innerHTML = '<main class="expired-page"><h1>会话已过期</h1><p>当前草稿已保存到浏览器本地存储。请关闭此标签页，并在游戏内重新获取编辑链接。</p></main>';
    }

    getDraftKey() {
        return `${LOCAL_STORAGE_PREFIX}${this.musicData?.id || this.sessionId}`;
    }

    saveToLocalStorage(force = false) {
        if (!this.musicData || (!force && !this.dirty)) return;

        const saveData = {
            id: this.musicData.id,
            title: this.musicData.title,
            tempo: this.musicData.tempo,
            beatSubdivision: this.musicData.beatSubdivision,
            timeSignature: this.musicData.timeSignature,
            maxTicks: this.musicData.maxTicks,
            notes: this.musicData.notes,
            savedAt: Date.now()
        };

        try {
            localStorage.setItem(this.getDraftKey(), JSON.stringify(saveData));
        } catch (error) {
            console.error('Failed to save draft:', error);
        }
    }

    queueDraftSave() {
        if (this.draftSaveTimer) {
            clearTimeout(this.draftSaveTimer);
        }
        this.draftSaveTimer = setTimeout(() => {
            this.saveToLocalStorage();
            this.draftSaveTimer = null;
        }, DRAFT_SAVE_DELAY);
    }

    loadFromLocalStorage() {
        try {
            const saved = localStorage.getItem(this.getDraftKey());
            if (!saved) return;

            const saveData = JSON.parse(saved);
            const savedAt = saveData.savedAt || 0;
            const hoursSinceSaved = (Date.now() - savedAt) / (1000 * 60 * 60);

            if (hoursSinceSaved < 24) {
                this.showRestoreDraftPrompt(saveData);
            } else {
                localStorage.removeItem(this.getDraftKey());
            }
        } catch (error) {
            console.error('Failed to load draft:', error);
        }
    }

    showRestoreDraftPrompt(saveData) {
        const dialog = this.showDialog({
            className: 'restore-draft-dialog',
            title: '发现未保存草稿',
            lines: [
                `标题: ${saveData.title || 'Untitled'}`,
                `音符: ${Array.isArray(saveData.notes) ? saveData.notes.length : 0}`,
                `保存时间: ${new Date(saveData.savedAt).toLocaleString()}`
            ],
            buttons: [
                { id: 'restore', label: '恢复草稿', primary: true },
                { id: 'discard', label: '丢弃' }
            ]
        });

        dialog.querySelector('[data-dialog-action="restore"]').onclick = () => {
            this.applyMusicData({
                ...this.musicData,
                ...saveData,
                notes: saveData.notes || []
            }, { dirty: true, focusContent: true });
            this.showStatus('草稿已恢复');
            dialog.remove();
        };

        dialog.querySelector('[data-dialog-action="discard"]').onclick = () => {
            localStorage.removeItem(this.getDraftKey());
            dialog.remove();
        };
    }

    async loadSettings() {
        try {
            const response = await fetch('/api/settings');
            if (!response.ok) return;

            const settings = await response.json();
            this.settings = {
                ...this.settings,
                ...settings,
                timeSignatures: Array.isArray(settings.timeSignatures) && settings.timeSignatures.length > 0
                    ? settings.timeSignatures
                    : TIME_SIGNATURES
            };
            this.settings.maxTicks = this.normalizeInt(this.settings.maxTicks, 10000, TICKS_PER_VIEW, 1000000);
            this.configuredMaxPitch = this.settings.enable10octave
                ? this.normalizeInt(this.settings.extendedMaxPitch, 119, DEFAULT_MAX_PITCH, 255)
                : this.normalizeInt(this.settings.defaultMaxPitch, DEFAULT_MAX_PITCH, 0, 255);
            this.maxPitch = this.configuredMaxPitch;
            this.maxTickLimit = this.settings.maxTicks;
            this.maxTicks = this.createDisplayTimelineLength(this.getMaxNoteTick(), this.maxTickLimit);
            this.updatePropertyLimits();
            this.applyEditorCssMetrics();
        } catch (error) {
            console.error('Failed to load settings:', error);
        }
    }

    async loadInstruments() {
        try {
            const response = await fetch('/api/instruments');
            if (!response.ok) return;

            this.instruments = this.mergeInstrumentCatalog(await response.json());
            this.instrumentMap = new Map(this.instruments.map(inst => [inst.id, inst]));
            this.instrumentIds = new Set(this.instruments.map(inst => inst.id));
            if (!this.instrumentIds.has(this.selectedInstrument) && this.instruments.length > 0) {
                this.selectedInstrument = this.instruments[0].id;
            }
            this.renderInstrumentList();
        } catch (error) {
            console.error('Failed to load instruments:', error);
        }
    }

    mergeInstrumentCatalog(serverInstruments) {
        const merged = new Map();
        for (const inst of STANDARD_NBS_INSTRUMENTS) {
            merged.set(inst.id, { ...inst, standardNbs: true });
        }
        if (Array.isArray(serverInstruments)) {
            for (const inst of serverInstruments) {
                if (!inst?.id) continue;
                const existing = merged.get(inst.id) || {};
                merged.set(inst.id, {
                    ...existing,
                    ...inst,
                    material: inst.material || existing.material || DEFAULT_INSTRUMENT_MATERIALS[inst.id] || 'note_block',
                    label: inst.label || existing.label || inst.id
                });
            }
        }
        return [...merged.values()];
    }

    async loadMusic() {
        try {
            const response = await this.apiFetch('/api/music');
            if (!response.ok) {
                if (this.handleApiUnauthorized(response)) return;
                this.showStatus(`加载失败 (${response.status})`);
                return;
            }

            const data = await response.json();
            this.applyMusicData(data, { dirty: false, focusContent: true });
            this.showStatus('已加载');
        } catch (error) {
            console.error('Failed to load music:', error);
            this.showStatus('加载失败');
        }
    }

    normalizeMusicData(data) {
        const rawNotes = Array.isArray(data?.notes) ? data.notes : [];
        const notes = [];
        const seen = new Set();

        for (const raw of rawNotes) {
            const pitch = this.normalizeInt(raw.pitch, -1, 0, 255);
            const tick = this.normalizeInt(raw.tick, -1, 0, this.settings.maxTicks);
            if (pitch < 0 || tick < 0) continue;
            if (tick >= (this.settings.maxTicks || 10000)) continue;

            const key = this.noteKey(pitch, tick);
            if (seen.has(key)) continue;
            seen.add(key);

            const instruments = this.normalizeInstruments(raw.instruments);
            notes.push({ pitch, tick, instruments });
        }

        const payloadMaxPitch = this.normalizeInt(data?.maxPitch, this.configuredMaxPitch, 0, 255);
        const highestPitch = notes.reduce((max, note) => Math.max(max, note.pitch), payloadMaxPitch);
        this.maxPitch = Math.max(this.configuredMaxPitch, Math.min(highestPitch, this.settings.extendedMaxPitch || highestPitch));

        const maxNoteTick = notes.reduce((max, note) => Math.max(max, note.tick), 0);
        const configuredMaxTicks = this.normalizeInt(data?.maxTicks, this.settings.maxTicks, TICKS_PER_VIEW, 1000000);
        this.maxTickLimit = Math.max(TICKS_PER_VIEW, configuredMaxTicks);
        this.maxTicks = this.createDisplayTimelineLength(maxNoteTick, this.maxTickLimit);

        const timeSignature = this.settings.timeSignatures.includes(data?.timeSignature)
            ? data.timeSignature
            : '4/4';

        return {
            id: data?.id || this.musicData?.id || null,
            title: typeof data?.title === 'string' && data.title.trim() ? data.title.trim() : 'Untitled',
            tempo: this.normalizeInt(data?.tempo, 120, this.settings.minBpm, this.settings.maxBpm),
            beatSubdivision: this.normalizeInt(data?.beatSubdivision, this.settings.defaultBeatSubdivision || 4, 1, 16),
            timeSignature,
            maxTicks: this.maxTicks,
            description: typeof data?.description === 'string' ? data.description : '',
            notes
        };
    }

    normalizeInstruments(rawInstruments) {
        const instruments = Array.isArray(rawInstruments)
            ? rawInstruments.map(inst => typeof inst === 'object' ? inst.id : inst)
            : [];
        const normalized = [];

        for (const instrument of instruments) {
            if (this.instrumentIds.size === 0 || this.instrumentIds.has(instrument)) {
                if (instrument && !normalized.includes(instrument)) {
                    normalized.push(instrument);
                }
            }
        }

        if (normalized.length === 0) {
            normalized.push(this.selectedInstrument);
        }
        return normalized;
    }

    applyMusicData(data, { dirty, focusContent = false }) {
        this.musicData = this.normalizeMusicData(data);
        this.rebuildIndexes();
        this.selectedNotes = this.selectedNotes.filter(note => this.noteIndex.has(this.noteKey(note.pitch, note.tick)));
        this.clampVisibleTick();
        this.renderEditor();
        this.updateHeader();
        this.updateSelectedNoteInfo();
        this.updateTimelineControls();
        this.markDirty(dirty);
        if (focusContent) {
            this.focusViewportOnContent();
        }
    }

    normalizeInt(value, fallback, min, max) {
        const parsed = Number.parseInt(value, 10);
        if (!Number.isFinite(parsed)) return fallback;
        return Math.max(min, Math.min(max, parsed));
    }

    noteKey(pitch, tick) {
        return `${pitch}-${tick}`;
    }

    rebuildIndexes() {
        this.noteIndex.clear();
        this.notesByTick.clear();

        if (!this.musicData?.notes) return;
        for (const note of this.musicData.notes) {
            this.noteIndex.set(this.noteKey(note.pitch, note.tick), note);
            if (!this.notesByTick.has(note.tick)) {
                this.notesByTick.set(note.tick, []);
            }
            this.notesByTick.get(note.tick).push(note);
        }
    }

    getNote(pitch, tick) {
        return this.noteIndex.get(this.noteKey(pitch, tick));
    }

    getMaxNoteTick() {
        if (!this.musicData?.notes?.length) return 0;
        return this.musicData.notes.reduce((max, note) => Math.max(max, note.tick), 0);
    }

    createDisplayTimelineLength(maxNoteTick, hardLimit = this.maxTickLimit) {
        const limit = Math.max(TICKS_PER_VIEW, hardLimit || this.settings.maxTicks || TICKS_PER_VIEW);
        const contentLength = Math.max(TICKS_PER_VIEW, maxNoteTick + TIMELINE_PADDING_TICKS);
        return Math.min(limit, contentLength);
    }

    ensureTimelineCapacity(tick) {
        const requiredLength = this.createDisplayTimelineLength(Math.max(this.getMaxNoteTick(), tick), this.maxTickLimit);
        if (requiredLength <= this.maxTicks) {
            return false;
        }
        this.maxTicks = requiredLength;
        if (this.musicData) {
            this.musicData.maxTicks = this.maxTicks;
        }
        this.updateTimelineControls();
        this.updateHeader();
        return true;
    }

    getGridContainer() {
        return document.querySelector('.grid-container');
    }

    getPitchRowTop(pitch) {
        const clampedPitch = Math.max(0, Math.min(this.maxPitch, pitch));
        return RULER_HEIGHT + (this.maxPitch - clampedPitch) * this.noteRowHeight;
    }

    scrollPitchIntoView(pitch, center = true) {
        const container = this.getGridContainer();
        if (!container) return;

        const rowTop = this.getPitchRowTop(pitch);
        const viewportHeight = Math.max(1, container.clientHeight - RULER_HEIGHT);
        const target = center
            ? rowTop - (viewportHeight / 2) + (this.noteRowHeight / 2)
            : rowTop - RULER_HEIGHT;
        container.scrollTop = Math.max(0, target);
        this.updateVerticalScrollControl();
    }

    isPitchVisible(pitch) {
        const container = this.getGridContainer();
        if (!container) return true;

        const rowTop = this.getPitchRowTop(pitch);
        const rowBottom = rowTop + this.noteRowHeight;
        const visibleTop = container.scrollTop + RULER_HEIGHT;
        const visibleBottom = container.scrollTop + container.clientHeight;
        return rowTop >= visibleTop && rowBottom <= visibleBottom;
    }

    ensurePitchVisible(pitch) {
        if (!this.isPitchVisible(pitch)) {
            this.scrollPitchIntoView(pitch, true);
        }
    }

    focusViewportOnContent() {
        const notes = this.musicData?.notes || [];
        if (notes.length === 0) {
            this.visibleTickStart = 0;
            this.updateTimelineControls();
            this.renderTimeRuler();
            this.renderNoteGrid();
            requestAnimationFrame(() => this.scrollPitchIntoView(Math.min(this.maxPitch, DEFAULT_MAX_PITCH), false));
            return;
        }

        const firstTick = notes.reduce((min, note) => Math.min(min, note.tick), notes[0].tick);
        const focusPitch = notes.reduce((max, note) => Math.max(max, note.pitch), notes[0].pitch);
        this.ensureTickVisible(firstTick, true);
        requestAnimationFrame(() => this.scrollPitchIntoView(focusPitch, true));
    }

    refreshCell(pitch, tick) {
        const cell = document.querySelector(`.note-cell[data-pitch="${pitch}"][data-tick="${tick}"]`);
        if (!cell) return;

        const note = this.getNote(pitch, tick);
        const selected = this.selectedNotes.some(item => item.pitch === pitch && item.tick === tick);
        this.decorateNoteCell(cell, note, pitch, tick, selected);
    }

    refreshVisibleTick(tick) {
        document.querySelectorAll(`.note-cell[data-tick="${tick}"]`).forEach(cell => {
            const pitch = this.normalizeInt(cell.dataset.pitch, -1, 0, this.maxPitch);
            if (pitch < 0) return;
            const note = this.getNote(pitch, tick);
            const selected = this.selectedNotes.some(item => item.pitch === pitch && item.tick === tick);
            this.decorateNoteCell(cell, note, pitch, tick, selected);
        });
    }

    clampVisibleTick() {
        const maxStart = Math.max(0, this.maxTicks - TICKS_PER_VIEW);
        this.visibleTickStart = Math.max(0, Math.min(Math.floor(this.visibleTickStart), maxStart));
    }

    setVisibleTickStart(nextStart) {
        const maxStart = Math.max(0, this.maxTicks - TICKS_PER_VIEW);
        const clamped = this.normalizeInt(nextStart, this.visibleTickStart, 0, maxStart);
        if (clamped === this.visibleTickStart) return false;

        this.visibleTickStart = clamped;
        this.renderTimeRuler();
        this.shiftVisibleTickWindow();
        this.updateTimelineControls();
        this.updatePlayCursor();
        return true;
    }

    // Scroll reuses the rendered grid instead of rebuilding all cells: only the tick each
    // cell points at changes, so re-decorate in place. The 64x120 rebuild is reserved for
    // full repaints (initial load, zoom) where cell count or metrics actually change.
    shiftVisibleTickWindow() {
        const grid = document.getElementById('note-grid');
        if (!grid) return;
        const selected = new Set(this.selectedNotes.map(note => this.noteKey(note.pitch, note.tick)));
        const visibleEnd = Math.min(this.maxTicks, this.visibleTickStart + TICKS_PER_VIEW);
        const rows = grid.children;
        for (let rowIdx = 0; rowIdx < rows.length; rowIdx++) {
            // renderNoteGrid fills rows top-down from maxPitch to 0, so the row index is
            // the pitch offset.
            const pitch = this.maxPitch - rowIdx;
            const cells = rows[rowIdx].children;
            for (let col = 0; col < cells.length; col++) {
                const tick = this.visibleTickStart + col;
                if (tick >= visibleEnd) {
                    cells[col].style.display = 'none';
                    continue;
                }
                cells[col].style.display = '';
                cells[col].dataset.tick = tick;
                const key = this.noteKey(pitch, tick);
                this.decorateNoteCell(cells[col], this.noteIndex.get(key), pitch, tick, selected.has(key));
            }
        }
    }

    renderInstrumentList() {
        const container = document.getElementById('instrument-list');
        if (!container) return;
        container.innerHTML = '';

        this.instruments.forEach(inst => {
            const item = document.createElement('button');
            item.type = 'button';
            item.className = 'instrument-item' + (inst.id === this.selectedInstrument ? ' selected' : '');
            item.dataset.id = inst.id;
            item.style.setProperty('--instrument-color', this.getInstrumentColor(inst.id));
            item.title = inst.customSound || inst.sound || inst.id;
            item.onclick = () => this.selectInstrument(inst.id);

            const block = document.createElement('span');
            block.className = `instrument-block nbs-sprite ${this.getInstrumentBlockClass(inst.id)} ${this.getInstrumentCssClass(inst.id)} ${this.getNbsInstrumentClass(inst.id)}`;
            block.setAttribute('aria-hidden', 'true');

            const label = document.createElement('span');
            label.className = 'instrument-label';
            label.textContent = inst.label || inst.id;

            item.append(block, label);
            container.appendChild(item);
        });
    }

    selectInstrument(name) {
        this.selectedInstrument = name;
        document.querySelectorAll('.instrument-item').forEach(item => {
            item.classList.toggle('selected', item.dataset.id === name);
        });
        this.showStatus(`当前乐器: ${this.getInstrumentLabel(name)}`);
    }

    renderEditor() {
        this.applyEditorCssMetrics();
        this.renderPianoKeys();
        this.renderTimeRuler();
        this.renderNoteGrid();
        this.updatePlayCursor();
    }

    applyEditorCssMetrics() {
        const root = document.documentElement;
        if (!root) return;
        root.style.setProperty('--visible-ticks', String(TICKS_PER_VIEW));
        root.style.setProperty('--visible-pitches', String(this.maxPitch + 1));
        root.style.setProperty('--tick-width', this.tickWidth + 'px');
        root.style.setProperty('--note-row-height', this.noteRowHeight + 'px');
    }

    renderPianoKeys() {
        const container = document.getElementById('piano-keys');
        if (!container) return;
        container.innerHTML = '';

        for (let pitch = this.maxPitch; pitch >= 0; pitch--) {
            const key = document.createElement('div');
            const noteIndex = pitch % 12;
            const isBlack = [1, 3, 6, 8, 10].includes(noteIndex);
            key.className = 'piano-key ' + (isBlack ? 'black' : 'white');
            key.textContent = this.getNoteName(pitch);
            key.title = `试听 ${this.getNoteName(pitch)}`;
            key.onclick = () => this.previewPitch(pitch);
            container.appendChild(key);
        }
    }

    getNoteName(pitch) {
        const octave = Math.floor(pitch / 12) + 1;
        const noteIndex = pitch % 12;
        return `${NOTE_NAMES[noteIndex]}${octave}`;
    }

    getInstrumentLabel(instrumentId) {
        return this.instrumentMap.get(instrumentId)?.label || instrumentId || 'Unknown';
    }

    getInstrumentMaterial(instrumentId) {
        const rawMaterial = this.instrumentMap.get(instrumentId)?.material
            || DEFAULT_INSTRUMENT_MATERIALS[instrumentId]
            || 'note_block';
        const normalized = String(rawMaterial)
            .trim()
            .toLowerCase()
            .replace(/[^a-z0-9]+/g, '_')
            .replace(/^_+|_+$/g, '');
        return normalized || 'note_block';
    }

    getInstrumentBlockClass(instrumentId) {
        return `block-${this.getInstrumentMaterial(instrumentId).replace(/_/g, '-')}`;
    }

    getInstrumentCssClass(instrumentId) {
        const normalized = String(instrumentId || 'harp')
            .toLowerCase()
            .replace(/[^a-z0-9]+/g, '-')
            .replace(/^-+|-+$/g, '');
        return `instrument-${normalized || 'harp'}`;
    }

    getNbsInstrumentAsset(instrumentId) {
        const normalized = String(instrumentId || '')
            .trim()
            .toUpperCase()
            .replace(/[^A-Z0-9]+/g, '_')
            .replace(/^_+|_+$/g, '');
        return NBS_INSTRUMENT_ASSETS[normalized] || 'custom';
    }

    getNbsInstrumentClass(instrumentId) {
        return `nbs-${this.getNbsInstrumentAsset(instrumentId)}`;
    }

    getNoteCellLabel(note, pitch) {
        if (!note) return '';
        return this.getNoteName(pitch).replace(/\d+$/, '');
    }

    getInstrumentColor(instrumentId) {
        const colors = {
            HARP: '#d65d6d',
            BASS: '#a67848',
            BASS_DRUM: '#7c858e',
            SNARE_DRUM: '#d0b56b',
            CLICKS: '#6db4d8',
            GUITAR: '#c18a55',
            FLUTE: '#72b89f',
            BELL: '#d6b94f',
            CHIME: '#83b9e4',
            XYLOPHONE: '#d99a66',
            IRON_XYLOPHONE: '#aeb9c2',
            COW_BELL: '#96765a',
            DIDGERIDOO: '#d98255',
            BIT: '#6ecf82',
            BANJO: '#cab563',
            PLING: '#d7d86c',
            TRUMPET: '#d39154',
            TRUMPET_EXPOSED: '#bf8b65',
            TRUMPET_WEATHERED: '#62a88d',
            TRUMPET_OXIDIZED: '#4da3a0'
        };
        return colors[instrumentId] || this.colorFromString(instrumentId || 'note');
    }

    getInstrumentAccentColor(instrumentId) {
        return this.adjustColor(this.getInstrumentColor(instrumentId), -32);
    }

    colorFromString(value) {
        let hash = 0;
        for (let i = 0; i < value.length; i++) {
            hash = ((hash << 5) - hash) + value.charCodeAt(i);
            hash |= 0;
        }
        const hue = Math.abs(hash) % 360;
        return `hsl(${hue}, 58%, 58%)`;
    }

    adjustColor(color, amount) {
        if (!color.startsWith('#') || color.length !== 7) return color;
        const clamp = value => Math.max(0, Math.min(255, value));
        const red = clamp(parseInt(color.slice(1, 3), 16) + amount);
        const green = clamp(parseInt(color.slice(3, 5), 16) + amount);
        const blue = clamp(parseInt(color.slice(5, 7), 16) + amount);
        return `#${red.toString(16).padStart(2, '0')}${green.toString(16).padStart(2, '0')}${blue.toString(16).padStart(2, '0')}`;
    }

    getBeatTicks() {
        return Math.max(1, this.musicData?.beatSubdivision || this.settings.defaultBeatSubdivision || 4);
    }

    getMeasureTicks() {
        const signature = this.musicData?.timeSignature || '4/4';
        const beats = this.normalizeInt(signature.split('/')[0], 4, 1, 16);
        return Math.max(1, beats * this.getBeatTicks());
    }

    previewPitch(pitch) {
        this.playNote({
            pitch,
            tick: this.currentTick || 0,
            instruments: [this.selectedInstrument]
        });
        this.showStatus(`${this.getNoteName(pitch)} / ${this.getInstrumentLabel(this.selectedInstrument)}`);
    }

    renderTimeRuler() {
        const container = document.getElementById('time-ruler');
        if (!container) return;
        container.innerHTML = '';

        const visibleEnd = Math.min(this.maxTicks, this.visibleTickStart + TICKS_PER_VIEW);
        const beatTicks = this.getBeatTicks();
        const measureTicks = this.getMeasureTicks();
        for (let tick = this.visibleTickStart; tick < visibleEnd; tick++) {
            const marker = document.createElement('div');
            marker.className = 'time-marker';
            marker.dataset.tick = tick;
            if (tick === this.lastHighlightedTick) {
                marker.classList.add('playing');
            }
            if (tick % measureTicks === 0) {
                marker.classList.add('measure');
                marker.textContent = String(tick);
            } else if (tick % beatTicks === 0) {
                marker.classList.add('beat');
                marker.textContent = '·';
            } else {
                marker.textContent = '';
            }
            marker.title = `Tick ${tick}`;
            container.appendChild(marker);
        }
    }

    renderNoteGrid() {
        const container = document.getElementById('note-grid');
        if (!container) return;

        const selected = new Set(this.selectedNotes.map(note => this.noteKey(note.pitch, note.tick)));
        const visibleEnd = Math.min(this.maxTicks, this.visibleTickStart + TICKS_PER_VIEW);
        const fragment = document.createDocumentFragment();

        for (let pitch = this.maxPitch; pitch >= 0; pitch--) {
            const row = document.createElement('div');
            row.className = 'note-row';

            for (let tick = this.visibleTickStart; tick < visibleEnd; tick++) {
                const key = this.noteKey(pitch, tick);
                const note = this.noteIndex.get(key);
                const cell = document.createElement('button');
                cell.type = 'button';
                cell.dataset.pitch = pitch;
                cell.dataset.tick = tick;
                this.decorateNoteCell(cell, note, pitch, tick, selected.has(key));
                row.appendChild(cell);
            }

            fragment.appendChild(row);
        }

        container.innerHTML = '';
        container.appendChild(fragment);
        requestAnimationFrame(() => this.updateVerticalScrollControl());
    }

    // Container-level delegation: a 64x120 grid would otherwise attach six listeners to every
    // cell. The grid owns one of each, and the handlers resolve the cell via event.target.
    bindNoteGridEvents() {
        const grid = document.getElementById('note-grid');
        if (!grid || grid.dataset.delegated) return;
        grid.dataset.delegated = 'true';

        grid.addEventListener('pointerdown', event => {
            const cell = this.resolveCellFromEvent(event);
            if (!cell) return;
            this.handleCellPointerDown(event, cell.pitch, cell.tick);
        });
        grid.addEventListener('click', event => {
            const cell = this.resolveCellFromEvent(event);
            if (!cell) return;
            this.handleCellClick(event, cell.pitch, cell.tick);
        });
        grid.addEventListener('contextmenu', event => {
            const cell = this.resolveCellFromEvent(event);
            if (!cell) return;
            this.handleCellRightClick(event, cell.pitch, cell.tick);
        });
    }

    resolveCellFromEvent(event) {
        const cellElement = event.target?.closest?.('.note-cell');
        if (!cellElement) return null;
        const pitch = this.normalizeInt(cellElement.dataset.pitch, -1, 0, this.maxPitch);
        const tick = this.normalizeInt(cellElement.dataset.tick, -1, 0, this.maxTickLimit);
        if (pitch < 0 || tick < 0) return null;
        return { cell: cellElement, pitch, tick };
    }

    isSelectionGesture(event) {
        return this.isToggleSelectionGesture(event) || event.shiftKey;
    }

    isToggleSelectionGesture(event) {
        return event.ctrlKey || event.metaKey || event.altKey;
    }

    getCellFromElement(element) {
        const cell = element?.closest?.('.note-cell');
        if (!cell) return null;

        const pitch = this.normalizeInt(cell.dataset.pitch, -1, 0, this.maxPitch);
        const tick = this.normalizeInt(cell.dataset.tick, -1, 0, this.maxTickLimit);
        if (pitch < 0 || tick < 0) return null;
        return { cell, pitch, tick };
    }

    getCellFromPointerEvent(event) {
        if (typeof document.elementFromPoint !== 'function') return null;
        return this.getCellFromElement(document.elementFromPoint(event.clientX, event.clientY))
            || this.getCellFromPointerCoordinates(event);
    }

    getCellFromPointerCoordinates(event) {
        const grid = document.getElementById('note-grid');
        const container = this.getGridContainer();
        if (!grid || !container) return null;

        const visibleEnd = Math.min(this.maxTicks, this.visibleTickStart + TICKS_PER_VIEW);
        const visibleTicks = Math.max(0, visibleEnd - this.visibleTickStart);
        if (visibleTicks <= 0) return null;

        const containerRect = container.getBoundingClientRect();
        const withinHorizontalReach = event.clientX >= containerRect.left - DRAG_EDGE_SCROLL_ZONE
            && event.clientX <= containerRect.right + DRAG_EDGE_SCROLL_ZONE;
        const withinVerticalReach = event.clientY >= containerRect.top + RULER_HEIGHT - DRAG_EDGE_SCROLL_ZONE
            && event.clientY <= containerRect.bottom + DRAG_EDGE_SCROLL_ZONE;
        if (!withinHorizontalReach || !withinVerticalReach) return null;

        const rect = grid.getBoundingClientRect();
        const column = Math.max(0, Math.min(visibleTicks - 1, Math.floor((event.clientX - rect.left) / this.tickWidth)));
        const row = Math.max(0, Math.min(this.maxPitch, Math.floor((event.clientY - rect.top) / this.noteRowHeight)));
        const pitch = this.maxPitch - row;
        const tick = this.visibleTickStart + column;
        return { cell: null, pitch, tick };
    }

    trackDragPointer(event) {
        this.dragPointerId = event.pointerId;
        this.dragCaptureTarget = event.currentTarget;
        if (this.dragCaptureTarget?.setPointerCapture) {
            try {
                this.dragCaptureTarget.setPointerCapture(event.pointerId);
            } catch (ignored) {
                // Some browsers can reject capture after the pointer already ended.
            }
        }
    }

    isActiveDragPointer(event) {
        return this.dragPointerId === null || event.pointerId === this.dragPointerId;
    }

    releaseDragPointer(event) {
        if (this.dragPointerId !== null && this.dragCaptureTarget?.releasePointerCapture) {
            try {
                this.dragCaptureTarget.releasePointerCapture(this.dragPointerId);
            } catch (ignored) {
                // Pointer capture may already be gone after a browser cancel.
            }
        }
        this.dragPointerId = null;
        this.dragCaptureTarget = null;
    }

    refreshSelectionClasses() {
        const selected = new Set(this.selectedNotes.map(note => this.noteKey(note.pitch, note.tick)));
        document.querySelectorAll('.note-cell').forEach(cell => {
            const key = this.noteKey(
                this.normalizeInt(cell.dataset.pitch, -1, 0, this.maxPitch),
                this.normalizeInt(cell.dataset.tick, -1, 0, this.maxTickLimit)
            );
            cell.classList.toggle('selected', selected.has(key));
        });
    }

    selectSingleNote(pitch, tick, { render = true, showStatus = true } = {}) {
        this.selectedNotes = [{ pitch, tick }];
        this.lastSelectedNote = { pitch, tick };
        if (render) {
            this.renderNoteGrid();
        } else {
            this.refreshSelectionClasses();
        }
        this.updateSelectedNoteInfo();
        if (showStatus) this.showStatus(`${this.getNoteName(pitch)} / Tick ${tick}`);
    }

    decorateNoteCell(cell, note, pitch, tick, selected) {
        cell.className = 'note-cell';
        cell.dataset.pitch = pitch;
        cell.dataset.tick = tick;

        const beatTicks = this.getBeatTicks();
        const measureTicks = this.getMeasureTicks();
        if (tick % measureTicks === 0) {
            cell.classList.add('measure-line');
        } else if (tick % beatTicks === 0) {
            cell.classList.add('beat-line');
        }

        cell.style.removeProperty('--note-color');
        cell.style.removeProperty('--note-accent');
        delete cell.dataset.instrumentCount;
        delete cell.dataset.material;
        cell.textContent = '';

        if (note) {
            const primaryInstrument = note.instruments?.[0] || this.selectedInstrument;
            cell.classList.add('has-note');
            cell.dataset.material = this.getInstrumentMaterial(primaryInstrument);
            if ((note.instruments?.length || 0) > 1) {
                cell.classList.add('multi-note');
                cell.dataset.instrumentCount = String(note.instruments.length);
            }
            cell.style.setProperty('--note-color', this.getInstrumentColor(primaryInstrument));
            cell.style.setProperty('--note-accent', this.getInstrumentAccentColor(primaryInstrument));

            const block = document.createElement('span');
            block.className = `note-block nbs-sprite ${this.getInstrumentBlockClass(primaryInstrument)} ${this.getInstrumentCssClass(primaryInstrument)} ${this.getNbsInstrumentClass(primaryInstrument)}`;
            block.setAttribute('aria-hidden', 'true');
            cell.appendChild(block);

            const label = document.createElement('span');
            label.className = 'note-chip';
            label.textContent = this.getNoteCellLabel(note, pitch);
            cell.appendChild(label);
        }

        if (selected) cell.classList.add('selected');

        const instrumentLabels = (note?.instruments || []).map(inst => this.getInstrumentLabel(inst)).join(', ');
        cell.title = note
            ? `${this.getNoteName(pitch)} / Tick ${tick} / ${instrumentLabels}`
            : `${this.getNoteName(pitch)} / Tick ${tick}`;
    }

    handleCellPointerDown(event, pitch, tick) {
        if (event.button !== 0 && event.button !== 2) return;
        if (this.isSelectionGesture(event)) return;

        const note = this.getNote(pitch, tick);
        if (event.button === 0 && note) {
            event.preventDefault();
            if (!this.isSelectedNote(pitch, tick)) {
                this.selectSingleNote(pitch, tick, { render: false, showStatus: true });
                this.suppressNextClick = true;
            }
            if (this.startMoveDrag(pitch, tick)) {
                this.trackDragPointer(event);
            }
            return;
        }

        event.preventDefault();
        this.suppressNextClick = true;
        this.dragAction = event.button === 2 ? 'erase' : 'draw';
        this.dragTouched.clear();
        this.trackDragPointer(event);
        this.saveStateForUndo();
        this.applyDragAt(pitch, tick);
    }

    handleGlobalPointerMove(event) {
        if (!this.moveDrag && !this.dragAction) return;
        this.autoScrollDuringDrag(event);
        this.handlePointerDrag(event);
    }

    handlePointerDrag(event, fallbackPitch = null, fallbackTick = null) {
        if (!this.isActiveDragPointer(event)) return;
        if (event.buttons === 0) {
            this.endDrag(event);
            return;
        }

        const target = this.getCellFromPointerEvent(event) || (fallbackPitch !== null && fallbackTick !== null
            ? { pitch: fallbackPitch, tick: fallbackTick }
            : null);
        if (!target) return;

        event.preventDefault();
        if (this.moveDrag) {
            this.updateMoveDrag(target.pitch, target.tick);
            return;
        }
        if (this.dragAction) this.applyDragAt(target.pitch, target.tick);
    }

    applyDragAt(pitch, tick) {
        const key = this.noteKey(pitch, tick);
        if (this.dragTouched.has(key)) return;
        this.dragTouched.add(key);

        if (this.dragAction === 'erase') {
            this.removeNote(pitch, tick, { skipUndo: true, quiet: true });
        } else {
            this.addOrUpdateNote(pitch, tick, { skipUndo: true, quiet: true });
        }
    }

    endDrag(event = null) {
        if (event && !this.isActiveDragPointer(event)) return;
        this.releaseDragPointer(event);
        if (this.moveDrag) {
            this.finishMoveDrag();
            return;
        }
        if (!this.dragAction) {
            this.stopEdgeScroll();
            return;
        }
        const touchedCount = this.dragTouched.size;
        this.dragAction = null;
        this.dragTouched.clear();
        this.stopEdgeScroll();
        if (touchedCount > 0) {
            this.showStatus('已更新音符');
        }
    }

    isSelectedNote(pitch, tick) {
        const key = this.noteKey(pitch, tick);
        return this.selectedNotes.some(note => this.noteKey(note.pitch, note.tick) === key);
    }

    startMoveDrag(pitch, tick) {
        if (!this.isSelectedNote(pitch, tick)) return false;
        const notes = this.selectedNotes
            .map(selection => this.getNote(selection.pitch, selection.tick))
            .filter(Boolean)
            .map(note => ({
                pitch: note.pitch,
                tick: note.tick,
                instruments: [...note.instruments]
            }));
        if (notes.length === 0) return false;

        this.moveDrag = {
            originPitch: pitch,
            originTick: tick,
            lastPitch: pitch,
            lastTick: tick,
            moved: false,
            notes
        };
        return true;
    }

    updateMoveDrag(pitch, tick) {
        if (!this.moveDrag || (pitch === this.moveDrag.lastPitch && tick === this.moveDrag.lastTick)) return;
        this.moveDrag.lastPitch = pitch;
        this.moveDrag.lastTick = tick;
        this.moveDrag.moved = true;
        this.suppressNextClick = true;
        this.updateMovePreview(this.moveDrag);
    }

    finishMoveDrag() {
        const move = this.moveDrag;
        this.moveDrag = null;
        this.stopEdgeScroll();
        this.clearMovePreview();
        if (!move || !move.moved) return;

        const deltaPitch = move.lastPitch - move.originPitch;
        const deltaTick = move.lastTick - move.originTick;
        if (deltaPitch === 0 && deltaTick === 0) return;

        if (!this.moveSelectedNotes(deltaPitch, deltaTick, move.notes)) {
            this.showStatus('无法移动到该位置');
        }
    }

    moveSelectedNotes(deltaPitch, deltaTick, sourceNotes = null) {
        const notes = sourceNotes || this.selectedNotes
            .map(selection => this.getNote(selection.pitch, selection.tick))
            .filter(Boolean)
            .map(note => ({
                pitch: note.pitch,
                tick: note.tick,
                instruments: [...note.instruments]
            }));
        if (notes.length === 0) return false;

        const selectedKeys = new Set(notes.map(note => this.noteKey(note.pitch, note.tick)));
        const movedNotes = [];
        for (const note of notes) {
            const nextPitch = note.pitch + deltaPitch;
            const nextTick = note.tick + deltaTick;
            if (nextPitch < 0 || nextPitch > this.maxPitch || nextTick < 0 || nextTick >= this.maxTickLimit) {
                return false;
            }
            const targetKey = this.noteKey(nextPitch, nextTick);
            if (!selectedKeys.has(targetKey) && this.noteIndex.has(targetKey)) {
                return false;
            }
            movedNotes.push({
                pitch: nextPitch,
                tick: nextTick,
                instruments: [...note.instruments]
            });
        }

        this.saveStateForUndo();
        this.musicData.notes = this.musicData.notes
            .filter(note => !selectedKeys.has(this.noteKey(note.pitch, note.tick)))
            .concat(movedNotes);
        this.selectedNotes = movedNotes.map(note => ({ pitch: note.pitch, tick: note.tick }));
        this.lastSelectedNote = this.selectedNotes[this.selectedNotes.length - 1] || null;
        this.rebuildIndexes();
        this.ensureTimelineCapacity(Math.max(...movedNotes.map(note => note.tick)));
        this.clampVisibleTick();
        this.markDirty(true);
        this.renderTimeRuler();
        this.renderNoteGrid();
        this.updateHeader();
        this.updateTimelineControls();
        this.updateSelectedNoteInfo();
        this.showStatus(`已移动 ${movedNotes.length} 个音符`);
        return true;
    }

    updateMovePreview(move) {
        const deltaPitch = move.lastPitch - move.originPitch;
        const deltaTick = move.lastTick - move.originTick;
        this.clearMovePreview();
        if (deltaPitch === 0 && deltaTick === 0) return;

        const selectedKeys = new Set(move.notes.map(note => this.noteKey(note.pitch, note.tick)));
        let invalidTarget = false;
        const previewNotes = move.notes.map(note => {
            const pitch = note.pitch + deltaPitch;
            const tick = note.tick + deltaTick;
            const key = this.noteKey(pitch, tick);
            const outOfBounds = pitch < 0 || pitch > this.maxPitch || tick < 0 || tick >= this.maxTickLimit;
            const collides = !outOfBounds && !selectedKeys.has(key) && this.noteIndex.has(key);
            invalidTarget = invalidTarget || outOfBounds || collides;
            return { note, pitch, tick, key, outOfBounds };
        });

        document.querySelectorAll('.note-cell.selected').forEach(cell => {
            const key = this.noteKey(
                this.normalizeInt(cell.dataset.pitch, -1, 0, this.maxPitch),
                this.normalizeInt(cell.dataset.tick, -1, 0, this.maxTickLimit)
            );
            if (!selectedKeys.has(key)) return;
            cell.classList.add('move-preview-source');
            cell.classList.toggle('move-preview-invalid', invalidTarget);
        });

        previewNotes.forEach(preview => {
            if (preview.outOfBounds) return;
            const cell = document.querySelector(`.note-cell[data-pitch="${preview.pitch}"][data-tick="${preview.tick}"]`);
            if (!cell) return;

            const primaryInstrument = preview.note.instruments?.[0] || this.selectedInstrument;
            cell.classList.add('move-preview-target', invalidTarget ? 'move-preview-invalid' : 'move-preview-valid');
            cell.style.setProperty('--note-color', this.getInstrumentColor(primaryInstrument));
            cell.style.setProperty('--note-accent', this.getInstrumentAccentColor(primaryInstrument));

            const ghost = document.createElement('span');
            ghost.className = 'move-preview-ghost';

            const block = document.createElement('span');
            block.className = `note-block nbs-sprite ${this.getInstrumentBlockClass(primaryInstrument)} ${this.getInstrumentCssClass(primaryInstrument)} ${this.getNbsInstrumentClass(primaryInstrument)}`;
            block.setAttribute('aria-hidden', 'true');
            ghost.appendChild(block);

            const label = document.createElement('span');
            label.className = 'note-chip';
            label.textContent = this.getNoteCellLabel(preview.note, preview.pitch);
            ghost.appendChild(label);

            if ((preview.note.instruments?.length || 0) > 1) {
                const count = document.createElement('span');
                count.className = 'move-preview-count';
                count.textContent = String(preview.note.instruments.length);
                ghost.appendChild(count);
            }

            cell.appendChild(ghost);
        });
    }

    clearMovePreview() {
        document.querySelectorAll('.note-cell.move-preview-target').forEach(cell => {
            const pitch = this.normalizeInt(cell.dataset.pitch, -1, 0, this.maxPitch);
            const tick = this.normalizeInt(cell.dataset.tick, -1, 0, this.maxTickLimit);
            if (pitch >= 0 && tick >= 0) this.refreshCell(pitch, tick);
        });
        document.querySelectorAll('.move-preview-ghost').forEach(ghost => ghost.remove());
        document.querySelectorAll('.note-cell.move-preview-source, .note-cell.move-preview-target, .note-cell.move-preview-invalid, .note-cell.move-preview-valid').forEach(cell => {
            cell.classList.remove('move-preview-source', 'move-preview-target', 'move-preview-invalid', 'move-preview-valid');
        });
    }

    handleGridWheel(event) {
        if (this.sessionExpired) return;
        // Ctrl+wheel zooms the grid; plain wheel scrolls horizontally.
        if (event.ctrlKey || event.metaKey) {
            event.preventDefault();
            const factor = event.deltaY < 0 ? 1.12 : 1 / 1.12;
            this.setGridZoom(factor);
            return;
        }
        const horizontalDelta = Math.abs(event.deltaX) > Math.abs(event.deltaY)
            ? event.deltaX
            : (event.shiftKey ? event.deltaY : 0);
        if (horizontalDelta === 0) return;

        event.preventDefault();
        const direction = horizontalDelta > 0 ? 1 : -1;
        const ticks = direction * Math.max(1, Math.round(Math.abs(horizontalDelta) / this.tickWidth));
        this.setVisibleTickStart(this.visibleTickStart + ticks);
    }

    // Scales both axes from a single factor, clamped so the grid stays usable at both extremes,
    // then pushes the new metrics to the CSS vars every layout reads. The visible window stays
    // put on the tick the pointer started from. Renders are merged into one per animation frame
    // so a fast Ctrl+wheel cannot trigger a full grid rebuild per event.
    setGridZoom(factor) {
        if (this.zoomAccumulator === null) {
            this.zoomAccumulator = 1;
            requestAnimationFrame(() => {
                this.applyZoom(this.zoomAccumulator);
                this.zoomAccumulator = null;
            });
        }
        this.zoomAccumulator *= factor;
    }

    applyZoom(factor) {
        const nextTickWidth = Math.round(Math.max(16, Math.min(80, this.tickWidth * factor)));
        const nextRowHeight = Math.round(Math.max(16, Math.min(64, this.noteRowHeight * factor)));
        if (nextTickWidth === this.tickWidth && nextRowHeight === this.noteRowHeight) return;
        this.tickWidth = nextTickWidth;
        this.noteRowHeight = nextRowHeight;
        this.applyEditorCssMetrics();
        this.renderNoteGrid();
        this.renderTimeRuler();
        this.renderPianoKeys();
        this.updateTimelineControls();
        this.updatePlayCursor();
    }

    autoScrollDuringDrag(event) {
        if (!this.isActiveDragPointer(event)) {
            this.stopEdgeScroll();
            return;
        }
        const container = this.getGridContainer();
        if (!container) return;

        const rect = container.getBoundingClientRect();
        let tickDelta = 0;
        let rowDelta = 0;

        if (event.clientX > rect.right - DRAG_EDGE_SCROLL_ZONE) {
            const strength = (event.clientX - (rect.right - DRAG_EDGE_SCROLL_ZONE)) / DRAG_EDGE_SCROLL_ZONE;
            tickDelta = Math.ceil(Math.min(1, Math.max(0, strength)) * DRAG_EDGE_SCROLL_MAX_TICKS);
        } else if (event.clientX < rect.left + DRAG_EDGE_SCROLL_ZONE) {
            const strength = ((rect.left + DRAG_EDGE_SCROLL_ZONE) - event.clientX) / DRAG_EDGE_SCROLL_ZONE;
            tickDelta = -Math.ceil(Math.min(1, Math.max(0, strength)) * DRAG_EDGE_SCROLL_MAX_TICKS);
        }

        if (event.clientY > rect.bottom - DRAG_EDGE_SCROLL_ZONE) {
            const strength = (event.clientY - (rect.bottom - DRAG_EDGE_SCROLL_ZONE)) / DRAG_EDGE_SCROLL_ZONE;
            rowDelta = Math.ceil(Math.min(1, Math.max(0, strength)) * DRAG_EDGE_SCROLL_MAX_ROWS);
        } else if (event.clientY < rect.top + DRAG_EDGE_SCROLL_ZONE) {
            const strength = ((rect.top + DRAG_EDGE_SCROLL_ZONE) - event.clientY) / DRAG_EDGE_SCROLL_ZONE;
            rowDelta = -Math.ceil(Math.min(1, Math.max(0, strength)) * DRAG_EDGE_SCROLL_MAX_ROWS);
        }

        if (tickDelta !== 0) {
            this.setVisibleTickStart(this.visibleTickStart + tickDelta);
        }
        if (rowDelta !== 0) {
            this.setVerticalScrollTop(container.scrollTop + rowDelta * this.noteRowHeight);
        }
        if (tickDelta !== 0 || rowDelta !== 0) {
            this.startEdgeScroll(event, tickDelta, rowDelta);
        } else {
            this.stopEdgeScroll();
        }
    }

    startEdgeScroll(event, tickDelta, rowDelta) {
        this.edgeScrollPointer = {
            pointerId: event.pointerId,
            clientX: event.clientX,
            clientY: event.clientY
        };
        this.edgeScrollDelta = { tick: tickDelta, row: rowDelta };
        if (this.edgeScrollFrame) return;

        this.edgeScrollLastAt = 0;
        const step = timestamp => {
            if (!this.moveDrag && !this.dragAction) {
                this.stopEdgeScroll();
                return;
            }
            if (!this.edgeScrollPointer) {
                this.edgeScrollFrame = null;
                return;
            }
            if (!this.edgeScrollLastAt || timestamp - this.edgeScrollLastAt >= DRAG_EDGE_SCROLL_INTERVAL) {
                this.edgeScrollLastAt = timestamp;
                const container = this.getGridContainer();
                if (container) {
                    if (this.edgeScrollDelta.tick !== 0) {
                        this.setVisibleTickStart(this.visibleTickStart + this.edgeScrollDelta.tick);
                    }
                    if (this.edgeScrollDelta.row !== 0) {
                        this.setVerticalScrollTop(container.scrollTop + this.edgeScrollDelta.row * this.noteRowHeight);
                    }
                    const target = this.getCellFromPointerCoordinates(this.edgeScrollPointer);
                    if (target) {
                        if (this.moveDrag) {
                            this.updateMoveDrag(target.pitch, target.tick);
                        } else if (this.dragAction) {
                            this.applyDragAt(target.pitch, target.tick);
                        }
                    }
                }
            }
            this.edgeScrollFrame = requestAnimationFrame(step);
        };
        this.edgeScrollFrame = requestAnimationFrame(step);
    }

    stopEdgeScroll() {
        if (this.edgeScrollFrame) {
            cancelAnimationFrame(this.edgeScrollFrame);
        }
        this.edgeScrollFrame = null;
        this.edgeScrollPointer = null;
        this.edgeScrollDelta = { tick: 0, row: 0 };
        this.edgeScrollLastAt = 0;
    }

    handleCellClick(event, pitch, tick) {
        if (this.suppressNextClick) {
            this.suppressNextClick = false;
            return;
        }
        if (this.dragAction) return;

        const note = this.getNote(pitch, tick);
        if (note || this.isSelectionGesture(event)) {
            this.handleNoteSelectionClick(event, pitch, tick);
            return;
        }
        this.addOrUpdateNote(pitch, tick);
    }

    handleCellRightClick(event, pitch, tick) {
        event.preventDefault();
        if (!this.dragAction) {
            this.removeNote(pitch, tick);
        }
    }

    addOrUpdateNote(pitch, tick, options = {}) {
        if (tick >= this.maxTickLimit || pitch > this.maxPitch) {
            if (!options.quiet) this.showStatus('超出可编辑范围');
            return;
        }
        this.ensureTimelineCapacity(tick);

        const existing = this.getNote(pitch, tick);
        if (existing) {
            if (existing.instruments.includes(this.selectedInstrument)) {
                this.selectedNotes = [{ pitch, tick }];
                this.lastSelectedNote = { pitch, tick };
                if (options.quiet) {
                    this.refreshCell(pitch, tick);
                } else {
                    this.renderNoteGrid();
                }
                this.updateSelectedNoteInfo();
                if (!options.quiet) this.showStatus(`${this.getNoteName(pitch)} / ${tick}`);
                return;
            }

            if (!options.skipUndo) this.saveStateForUndo();
            existing.instruments.push(this.selectedInstrument);
            this.markDirty(true);
            if (options.quiet) {
                this.refreshCell(pitch, tick);
            } else {
                this.renderNoteGrid();
            }
            this.updateSelectedNoteInfo();
            if (!options.quiet) this.showStatus('已添加乐器');
            return;
        }

        const note = {
            pitch,
            tick,
            instruments: [this.selectedInstrument]
        };

        if (!options.skipUndo) this.saveStateForUndo();
        this.musicData.notes.push(note);
        this.noteIndex.set(this.noteKey(pitch, tick), note);
        if (!this.notesByTick.has(tick)) {
            this.notesByTick.set(tick, []);
        }
        this.notesByTick.get(tick).push(note);
        this.markDirty(true);
        if (options.quiet) {
            this.refreshCell(pitch, tick);
        } else {
            this.renderNoteGrid();
        }
        this.updateHeader();
        if (!options.quiet) this.showStatus(`已添加 ${this.getNoteName(pitch)} / ${tick}`);
    }

    removeNote(pitch, tick, options = {}) {
        const key = this.noteKey(pitch, tick);
        if (!this.noteIndex.has(key)) return;

        if (!options.skipUndo) this.saveStateForUndo();
        const noteIndex = this.musicData.notes.findIndex(note => this.noteKey(note.pitch, note.tick) === key);
        if (noteIndex >= 0) {
            this.musicData.notes.splice(noteIndex, 1);
        }
        this.selectedNotes = this.selectedNotes.filter(note => this.noteKey(note.pitch, note.tick) !== key);
        this.noteIndex.delete(key);
        const tickNotes = this.notesByTick.get(tick);
        if (tickNotes) {
            const tickNoteIndex = tickNotes.findIndex(note => note.pitch === pitch && note.tick === tick);
            if (tickNoteIndex >= 0) {
                tickNotes.splice(tickNoteIndex, 1);
            }
            if (tickNotes.length === 0) {
                this.notesByTick.delete(tick);
            }
        }
        this.markDirty(true);
        if (options.quiet) {
            this.refreshCell(pitch, tick);
        } else {
            this.renderNoteGrid();
        }
        this.updateHeader();
        this.updateSelectedNoteInfo();
        if (!options.quiet) this.showStatus(`已删除 ${this.getNoteName(pitch)} / ${tick}`);
    }

    handleNoteSelectionClick(event, pitch, tick) {
        const key = this.noteKey(pitch, tick);
        if (!this.noteIndex.has(key)) {
            this.showStatus('该位置没有音符');
            return;
        }

        if (event.shiftKey && this.lastSelectedNote) {
            this.selectRange(this.lastSelectedNote, { pitch, tick }, this.isToggleSelectionGesture(event));
            this.renderNoteGrid();
            this.updateSelectedNoteInfo();
            return;
        }

        if (this.isToggleSelectionGesture(event)) {
            this.toggleNoteSelection(pitch, tick);
            this.lastSelectedNote = { pitch, tick };
            return;
        }

        if (this.selectedNotes.length === 1 && this.noteKey(this.selectedNotes[0].pitch, this.selectedNotes[0].tick) === key) {
            this.selectedNotes = [];
            this.lastSelectedNote = null;
            this.renderNoteGrid();
            this.updateSelectedNoteInfo();
            this.showStatus('已取消选择');
            return;
        }

        this.selectedNotes = [{ pitch, tick }];
        this.lastSelectedNote = { pitch, tick };
        this.renderNoteGrid();
        this.updateSelectedNoteInfo();
        this.showStatus(`${this.getNoteName(pitch)} / Tick ${tick}`);
    }

    toggleNoteSelection(pitch, tick) {
        const key = this.noteKey(pitch, tick);
        const index = this.selectedNotes.findIndex(note => this.noteKey(note.pitch, note.tick) === key);
        if (index >= 0) {
            this.selectedNotes.splice(index, 1);
        } else {
            this.selectedNotes.push({ pitch, tick });
        }

        this.renderNoteGrid();
        this.updateSelectedNoteInfo();
    }

    selectRange(start, end, keepExisting) {
        const minPitch = Math.min(start.pitch, end.pitch);
        const maxPitch = Math.max(start.pitch, end.pitch);
        const minTick = Math.min(start.tick, end.tick);
        const maxTick = Math.max(start.tick, end.tick);
        const next = keepExisting
            ? new Map(this.selectedNotes.map(note => [this.noteKey(note.pitch, note.tick), note]))
            : new Map();

        for (const note of this.musicData.notes) {
            if (note.pitch < minPitch || note.pitch > maxPitch || note.tick < minTick || note.tick > maxTick) {
                continue;
            }
            next.set(this.noteKey(note.pitch, note.tick), { pitch: note.pitch, tick: note.tick });
        }

        this.selectedNotes = Array.from(next.values());
        this.lastSelectedNote = { pitch: end.pitch, tick: end.tick };
        this.showStatus(`已选中 ${this.selectedNotes.length} 个音符`);
    }

    updateSelectedNoteInfo() {
        const container = document.getElementById('selected-note-info');
        if (!container) return;
        container.innerHTML = '';

        if (this.selectedNotes.length === 0) {
            const empty = document.createElement('p');
            empty.textContent = '未选中音符';
            const hint = document.createElement('p');
            hint.className = 'selection-hint';
            hint.textContent = '点击选择；再次点击取消；Ctrl/⌘/Alt 点击多选；Shift 点击范围选择。';
            container.append(empty, hint);
            return;
        }

        if (this.selectedNotes.length === 1) {
            const selected = this.selectedNotes[0];
            const note = this.getNote(selected.pitch, selected.tick);
            const pitch = document.createElement('p');
            pitch.textContent = `音高: ${this.getNoteName(selected.pitch)}`;
            const tick = document.createElement('p');
            tick.textContent = `Tick: ${selected.tick}`;
            const instruments = document.createElement('p');
            instruments.textContent = `乐器: ${(note?.instruments || []).map(inst => this.getInstrumentLabel(inst)).join(', ')}`;
            const hint = document.createElement('p');
            hint.className = 'selection-hint';
            hint.textContent = '拖动已选音符或按方向键可移动；Ctrl/⌘/Alt 点击多选；Shift 点击范围选择。';
            container.append(pitch, tick, instruments, hint);
            return;
        }

        const count = document.createElement('p');
        count.textContent = `已选中 ${this.selectedNotes.length} 个音符`;
        const hint = document.createElement('p');
        hint.className = 'selection-hint';
        hint.textContent = '拖动任一已选音符或按方向键可移动整组选区；复制、删除会作用于全部已选音符。';
        container.append(count, hint);
    }

    updateHeader() {
        const musicName = document.getElementById('music-name');
        const noteCount = document.getElementById('note-count');
        const titleInput = document.getElementById('title-input');
        const bpmInput = document.getElementById('bpm-input');
        const subdivisionInput = document.getElementById('subdivision-input');
        const timeSignatureInput = document.getElementById('time-signature-input');
        const descriptionInput = document.getElementById('description-input');
        const maxTicks = document.getElementById('max-ticks');

        if (musicName) musicName.textContent = this.musicData?.title || 'Untitled';
        if (noteCount) noteCount.textContent = `${this.musicData?.notes?.length || 0} 音符`;
        if (titleInput) titleInput.value = this.musicData?.title || '';
        if (bpmInput) bpmInput.value = this.musicData?.tempo || 120;
        if (subdivisionInput) subdivisionInput.value = this.musicData?.beatSubdivision || 4;
        if (timeSignatureInput) timeSignatureInput.value = this.musicData?.timeSignature || '4/4';
        if (descriptionInput) descriptionInput.value = this.musicData?.description || '';
        if (maxTicks) maxTicks.textContent = String(this.maxTickLimit || this.maxTicks);

        this.updatePlaybackControls();
        this.updateSaveState();
    }

    updatePropertyLimits() {
        const bpmInput = document.getElementById('bpm-input');
        if (bpmInput) {
            bpmInput.min = String(this.settings.minBpm);
            bpmInput.max = String(this.settings.maxBpm);
        }

        const timeSignatureInput = document.getElementById('time-signature-input');
        if (timeSignatureInput) {
            timeSignatureInput.innerHTML = '';
            this.settings.timeSignatures.forEach(value => {
                const option = document.createElement('option');
                option.value = value;
                option.textContent = value;
                timeSignatureInput.appendChild(option);
            });
        }
    }

    updateTimelineControls() {
        const scrollInput = document.getElementById('scroll-horizontal');
        if (scrollInput) {
            const maxStart = Math.max(0, this.maxTicks - TICKS_PER_VIEW);
            scrollInput.min = '0';
            scrollInput.max = String(maxStart);
            scrollInput.step = '1';
            scrollInput.value = String(this.visibleTickStart);
            scrollInput.disabled = maxStart <= 0;
        }
        this.updateVerticalScrollControl();
    }

    updateVerticalScrollControl() {
        const scrollInput = document.getElementById('scroll-vertical');
        const container = this.getGridContainer();
        if (!scrollInput || !container) return;

        const maxScrollTop = Math.max(0, container.scrollHeight - container.clientHeight);
        scrollInput.min = '0';
        scrollInput.max = String(Math.ceil(maxScrollTop));
        scrollInput.step = String(this.noteRowHeight);
        if (document.activeElement !== scrollInput) {
            scrollInput.value = String(Math.round(Math.min(container.scrollTop, maxScrollTop)));
        }
        scrollInput.disabled = maxScrollTop <= 0;
    }

    setVerticalScrollTop(nextTop) {
        const container = this.getGridContainer();
        if (!container) return false;

        const maxScrollTop = Math.max(0, container.scrollHeight - container.clientHeight);
        const clamped = Math.max(0, Math.min(Number(nextTop) || 0, maxScrollTop));
        if (Math.round(container.scrollTop) === Math.round(clamped)) {
            this.updateVerticalScrollControl();
            return false;
        }
        container.scrollTop = clamped;
        this.updateVerticalScrollControl();
        return true;
    }

    updatePlaybackControls() {
        const playhead = document.getElementById('playhead-slider');
        if (playhead) {
            const maxTick = Math.max(this.getMaxNoteTick(), 0);
            playhead.max = String(maxTick);
            if (document.activeElement !== playhead) {
                playhead.value = String(Math.min(this.currentTick, maxTick));
            }
        }

        const tickDisplay = document.getElementById('tick-display');
        if (tickDisplay) tickDisplay.textContent = `Tick: ${this.currentTick}`;

        const volumeSlider = document.getElementById('volume-slider');
        const volumeValue = document.getElementById('volume-value');
        if (volumeSlider && document.activeElement !== volumeSlider) {
            volumeSlider.value = String(Math.round(this.volume * 100));
        }
        if (volumeValue) volumeValue.textContent = `${Math.round(this.volume * 100)}%`;

        const speedSlider = document.getElementById('speed-slider');
        const speedValue = document.getElementById('speed-value');
        if (speedSlider && document.activeElement !== speedSlider) {
            speedSlider.value = String(Math.round(this.playbackSpeed * 100));
        }
        if (speedValue) speedValue.textContent = `${this.playbackSpeed.toFixed(1)}x`;
    }

    // Rebuilds the play timer at the same tick so a speed change takes effect mid-playback.
    restartPlaybackAtCurrentTick() {
        if (this.playInterval) {
            clearInterval(this.playInterval);
            this.playInterval = null;
        }
        this.isPlaying = true;
        const tempo = Math.max(1, this.musicData?.tempo || 120);
        const subdivision = Math.max(1, this.musicData?.beatSubdivision || 4);
        const tickDuration = Math.max(20, 60000 / (tempo * subdivision) / this.playbackSpeed);
        this.playInterval = setInterval(() => {
            this.playTick(this.currentTick);
            this.currentTick++;
            if (this.currentTick > this.playEndTick) {
                this.stopMusic();
            }
        }, tickDuration);
    }

    updateSaveState() {
        const saveState = document.getElementById('save-state');
        const saveButton = document.getElementById('btn-save');
        if (saveState) {
            saveState.textContent = this.dirty ? '未保存' : '已保存';
            saveState.classList.toggle('dirty', this.dirty);
        }
        if (saveButton) {
            saveButton.disabled = this.saveInFlight;
        }
    }

    setupEventListeners() {
        document.getElementById('btn-save').onclick = () => this.saveMusic();
        document.getElementById('btn-play').onclick = () => this.playMusic();
        document.getElementById('btn-stop').onclick = () => this.stopMusic();
        document.getElementById('btn-copy').onclick = () => this.copyNotes();
        document.getElementById('btn-paste').onclick = () => this.pasteNotes();
        document.getElementById('btn-delete').onclick = () => this.deleteSelectedNotes();
        document.getElementById('btn-clear').onclick = () => this.clearAllNotes();
        document.getElementById('btn-undo').onclick = () => this.undo();
        document.getElementById('btn-redo').onclick = () => this.redo();
        document.getElementById('btn-import').onclick = () => this.openImportDialog();

        const playhead = document.getElementById('playhead-slider');
        if (playhead) {
            playhead.oninput = event => {
                const nextTick = this.normalizeInt(event.target.value, 0, 0, Math.max(this.getMaxNoteTick(), 0));
                this.seekToTick(nextTick);
            };
        }

        const volumeSlider = document.getElementById('volume-slider');
        if (volumeSlider) {
            volumeSlider.oninput = event => {
                this.volume = this.normalizeInt(event.target.value, 70, 0, 100) / 100;
                this.updatePlaybackControls();
            };
        }

        const speedSlider = document.getElementById('speed-slider');
        if (speedSlider) {
            speedSlider.oninput = event => {
                this.playbackSpeed = this.normalizeInt(event.target.value, 100, 25, 200) / 100;
                this.updatePlaybackControls();
                if (this.isPlaying) {
                    this.restartPlaybackAtCurrentTick();
                }
            };
        }

        document.getElementById('title-input').onchange = event => {
            const newTitle = event.target.value.trim();
            if (!newTitle || newTitle === this.musicData.title) return;
            this.saveStateForUndo();
            this.musicData.title = newTitle;
            this.markDirty(true);
            this.updateHeader();
        };

        document.getElementById('bpm-input').onchange = event => {
            const newBpm = this.normalizeInt(event.target.value, this.musicData.tempo, this.settings.minBpm, this.settings.maxBpm);
            if (newBpm === this.musicData.tempo) return;
            this.saveStateForUndo();
            this.musicData.tempo = newBpm;
            this.markDirty(true);
            this.updateHeader();
        };

        document.getElementById('time-signature-input').onchange = event => {
            const next = event.target.value;
            if (!this.settings.timeSignatures.includes(next) || next === this.musicData.timeSignature) return;
            this.saveStateForUndo();
            this.musicData.timeSignature = next;
            this.markDirty(true);
            this.updateHeader();
        };

        document.getElementById('subdivision-input').onchange = event => {
            const next = this.normalizeInt(event.target.value, this.musicData.beatSubdivision, 1, 16);
            if (next === this.musicData.beatSubdivision) return;
            this.saveStateForUndo();
            this.musicData.beatSubdivision = next;
            this.markDirty(true);
            this.updateHeader();
        };

        document.getElementById('description-input').onchange = event => {
            const next = event.target.value.slice(0, 1000);
            if (next === (this.musicData.description || '')) return;
            this.saveStateForUndo();
            this.musicData.description = next;
            this.markDirty(true);
            this.updateHeader();
        };

        document.addEventListener('keydown', event => this.handleKeyDown(event));

        const scrollInput = document.getElementById('scroll-horizontal');
        scrollInput.oninput = event => {
            this.setVisibleTickStart(event.target.value);
        };

        const verticalScrollInput = document.getElementById('scroll-vertical');
        verticalScrollInput.oninput = event => {
            this.setVerticalScrollTop(event.target.value);
        };

        const gridContainer = this.getGridContainer();
        gridContainer.onwheel = event => this.handleGridWheel(event);
        gridContainer.onscroll = () => this.updateVerticalScrollControl();
        this.bindNoteGridEvents();

        window.addEventListener('pointermove', event => this.handleGlobalPointerMove(event));
        window.addEventListener('pointerup', event => this.endDrag(event));
        window.addEventListener('pointercancel', event => this.endDrag(event));
        window.addEventListener('blur', () => this.endDrag());

        document.getElementById('import-cancel').onclick = () => this.closeImportDialog();
        document.getElementById('import-confirm').onclick = () => this.importMusic();
        document.getElementById('import-path-input').onkeydown = event => {
            if (event.key === 'Enter') {
                event.preventDefault();
                this.importMusic();
            }
        };

        window.addEventListener('beforeunload', event => {
            if (this.sessionExpired) return;
            if (!this.dirty) return;
            this.saveToLocalStorage(true);
            event.preventDefault();
            event.returnValue = '';
        });
    }

    handleKeyDown(event) {
        if (this.sessionExpired) return;
        if (this.handleSelectionMoveShortcut(event)) {
            return;
        }
        if (event.ctrlKey || event.metaKey) {
            switch (event.key.toLowerCase()) {
                case 's':
                    event.preventDefault();
                    this.saveMusic();
                    break;
                case 'c':
                    event.preventDefault();
                    this.copyNotes();
                    break;
                case 'v':
                    event.preventDefault();
                    this.pasteNotes();
                    break;
                case 'z':
                    event.preventDefault();
                    this.undo();
                    break;
                case 'y':
                    event.preventDefault();
                    this.redo();
                    break;
                default:
                    break;
            }
        } else if (event.key === 'Delete') {
            this.deleteSelectedNotes();
        }
    }

    handleSelectionMoveShortcut(event) {
        if (this.selectedNotes.length === 0) return false;
        const keyMap = {
            ArrowLeft: [0, -1],
            ArrowRight: [0, 1],
            ArrowUp: [1, 0],
            ArrowDown: [-1, 0]
        };
        const delta = keyMap[event.key];
        if (!delta) return false;

        event.preventDefault();
        const step = (event.ctrlKey || event.metaKey) ? this.getBeatTicks() : 1;
        const deltaPitch = delta[0];
        const deltaTick = delta[1] * step;
        if (!this.moveSelectedNotes(deltaPitch, deltaTick)) {
            this.showStatus('无法移动到该位置');
        }
        return true;
    }

    saveStateForUndo() {
        if (!this.musicData) return;
        this.undoStack.push(JSON.stringify(this.musicData));
        if (this.undoStack.length > MAX_UNDO_STATES) {
            this.undoStack.shift();
        }
        this.redoStack = [];
    }

    restoreState(serializedState) {
        const data = JSON.parse(serializedState);
        this.musicData = this.normalizeMusicData(data);
        this.rebuildIndexes();
        this.selectedNotes = this.selectedNotes.filter(note => this.noteIndex.has(this.noteKey(note.pitch, note.tick)));
        this.clampVisibleTick();
        this.renderEditor();
        this.updateHeader();
        this.updateSelectedNoteInfo();
        this.updateTimelineControls();
        this.markDirty(true);
    }

    undo() {
        if (this.undoStack.length === 0) {
            this.showStatus('没有可撤销的操作');
            return;
        }

        this.redoStack.push(JSON.stringify(this.musicData));
        this.restoreState(this.undoStack.pop());
        this.showStatus('已撤销');
    }

    redo() {
        if (this.redoStack.length === 0) {
            this.showStatus('没有可重做的操作');
            return;
        }

        this.undoStack.push(JSON.stringify(this.musicData));
        this.restoreState(this.redoStack.pop());
        this.showStatus('已重做');
    }

    markDirty(dirty) {
        if (this.sessionExpired && dirty) return;
        this.dirty = Boolean(dirty);
        if (this.dirty) {
            this.changeVersion++;
            this.queueDraftSave();
        }
        this.updateSaveState();
    }

    startAutoSave() {
        if (this.sessionExpired) return;
        this.stopAutoSave();
        this.autoSaveTimer = setInterval(() => {
            if (this.dirty) {
                this.saveMusic(true);
            }
        }, AUTO_SAVE_INTERVAL);
    }

    stopAutoSave() {
        if (this.autoSaveTimer) {
            clearInterval(this.autoSaveTimer);
            this.autoSaveTimer = null;
        }
    }

    async saveMusic(silent = false) {
        if (this.sessionExpired) return;
        if (!this.musicData) return;
        if (silent && !this.dirty) return;

        if (this.saveInFlight) {
            this.saveQueued = true;
            return;
        }

        this.saveInFlight = true;
        const versionAtStart = this.changeVersion;
        this.updateSaveState();
        if (!silent) this.showStatus('保存中...');

        const payload = {
            title: this.musicData.title,
            tempo: this.musicData.tempo,
            beatSubdivision: this.musicData.beatSubdivision,
            timeSignature: this.musicData.timeSignature,
            description: this.musicData.description || '',
            notes: this.musicData.notes
        };

        try {
            const response = await this.apiFetch('/api/music', {
                method: 'PUT',
                headers: { 'Content-Type': 'application/json' },
                body: JSON.stringify(payload)
            });

            if (response.ok) {
                this.lastSavedAt = Date.now();
                if (this.changeVersion === versionAtStart) {
                    this.markDirty(false);
                    localStorage.removeItem(this.getDraftKey());
                } else {
                    this.saveQueued = true;
                }
                if (!silent) this.showStatus('已保存');
            } else {
                if (this.handleApiUnauthorized(response)) return;
                const text = await response.text();
                this.showStatus(`保存失败 (${response.status}) ${text}`);
            }
        } catch (error) {
            console.error('Save failed:', error);
            this.showStatus(`保存失败: ${error.message}`);
        } finally {
            this.saveInFlight = false;
            this.updateSaveState();
            if (this.saveQueued) {
                this.saveQueued = false;
                if (this.dirty) {
                    this.saveMusic(true);
                }
            }
        }
    }

    async playMusic() {
        if (this.sessionExpired) return;
        if (this.isPlaying) return;

        if (!this.audioContext) {
            this.audioContext = new (window.AudioContext || window.webkitAudioContext)();
        }
        if (this.audioContext.state === 'suspended') {
            await this.audioContext.resume();
        }

        this.isPlaying = true;
        const maxNoteTick = this.getMaxNoteTick();
        if ((this.currentTick || 0) >= maxNoteTick) {
            this.currentTick = 0;
        }
        this.playStartTick = Math.max(0, Math.min(this.currentTick || 0, maxNoteTick));
        this.currentTick = this.playStartTick;
        const tempo = Math.max(1, this.musicData?.tempo || 120);
        const subdivision = Math.max(1, this.musicData?.beatSubdivision || 4);
        const tickDuration = Math.max(20, 60000 / (tempo * subdivision) / this.playbackSpeed);
        this.playEndTick = Math.max(maxNoteTick, this.playStartTick);

        this.playInterval = setInterval(() => {
            this.playTick(this.currentTick);
            this.currentTick++;

            if (this.currentTick > this.playEndTick) {
                this.stopMusic();
            }
        }, tickDuration);

        this.showStatus('播放中...');
    }

    playTick(tick) {
        const notesAtTick = this.notesByTick.get(tick) || [];
        notesAtTick.forEach(note => this.playNote(note));
        if (notesAtTick.length > 0) {
            this.ensurePitchVisible(Math.max(...notesAtTick.map(note => note.pitch)));
        }
        this.highlightPlayingTick(tick);
        this.pulseMetronome(tick);

        const tickDisplay = document.getElementById('tick-display');
        if (tickDisplay) tickDisplay.textContent = `Tick: ${tick}`;
        const playhead = document.getElementById('playhead-slider');
        if (playhead) playhead.value = String(tick);
    }

    // Flashes the metronome dot on every beat boundary (and each subdivision within the first
    // beat of a measure) so tempo is audible/visible without a sound source.
    pulseMetronome(tick) {
        const beatTicks = this.getBeatTicks();
        if (tick % beatTicks === 0) {
            this.flashMetronome('beat');
        }
        this.lastBeatTick = tick;
    }

    flashMetronome(kind) {
        const light = document.getElementById('metronome-light');
        if (!light) return;
        light.classList.remove('pulse-beat');
        // Reflow to restart the CSS transition for consecutive flashes.
        void light.offsetWidth;
        light.classList.add(kind === 'beat' ? 'pulse-beat' : 'pulse-beat');
    }

    highlightPlayingTick(tick) {
        this.ensureTickVisible(tick);
        this.lastHighlightedTick = tick;
        this.updatePlayCursor();
    }

    ensurePlayCursor() {
        const inner = document.getElementById('piano-roll-inner');
        if (!inner) return null;

        let cursor = document.getElementById('play-cursor');
        if (!cursor) {
            cursor = document.createElement('div');
            cursor.id = 'play-cursor';
            cursor.className = 'play-cursor';
            inner.appendChild(cursor);
        }
        return cursor;
    }

    updatePlayingRulerMarker() {
        document.querySelectorAll('.time-marker.playing')
            .forEach(marker => marker.classList.remove('playing'));

        if (this.lastHighlightedTick === null) return;
        const marker = document.querySelector(`.time-marker[data-tick="${this.lastHighlightedTick}"]`);
        if (marker) marker.classList.add('playing');
    }

    updatePlayCursor() {
        const cursor = this.ensurePlayCursor();
        if (!cursor) return;

        const tick = this.lastHighlightedTick;
        const visible = tick !== null
            && tick >= this.visibleTickStart
            && tick < this.visibleTickStart + TICKS_PER_VIEW;

        cursor.classList.toggle('visible', visible);
        cursor.style.setProperty('--play-cursor-column', visible ? String(tick - this.visibleTickStart) : '-1');
        this.updatePlayingRulerMarker();
    }

    playNote(note) {
        if (!this.audioContext) return;

        const oscillator = this.audioContext.createOscillator();
        const gainNode = this.audioContext.createGain();

        oscillator.connect(gainNode);
        gainNode.connect(this.audioContext.destination);

        oscillator.frequency.value = this.getPlaybackFrequency(note.pitch);
        oscillator.type = this.getWaveform(note.instruments?.[0]);

        const now = this.audioContext.currentTime;
        gainNode.gain.setValueAtTime(0.34 * this.volume, now);
        gainNode.gain.exponentialRampToValueAtTime(0.01, now + 0.24);

        oscillator.start(now);
        oscillator.stop(now + 0.26);
    }

    getWaveform(instrument) {
        switch (instrument) {
            case 'BASS':
            case 'BASS_DRUM':
                return 'square';
            case 'SNARE_DRUM':
            case 'CLICKS':
                return 'triangle';
            case 'BELL':
            case 'CHIME':
            case 'PLING':
                return 'sine';
            default:
                return 'sine';
        }
    }

    getPlaybackFrequency(pitch) {
        const effectivePitch = this.settings.enable10octave
            ? Math.max(0, Math.min(119, pitch))
            : Math.max(0, Math.min(DEFAULT_MAX_PITCH, pitch));
        const midiKey = MIDI_KEY_FOR_EDITOR_PITCH_ZERO + effectivePitch;
        return 440 * Math.pow(2, (midiKey - 69) / 12);
    }

    stopMusic() {
        this.isPlaying = false;
        if (this.playInterval) {
            clearInterval(this.playInterval);
            this.playInterval = null;
        }
        this.currentTick = 0;
        this.playStartTick = 0;
        this.playEndTick = 0;
        this.lastHighlightedTick = null;
        this.updatePlayCursor();
        this.updatePlaybackControls();
        this.showStatus('已停止');
    }

    seekToTick(tick) {
        this.currentTick = tick;
        if (!this.isPlaying) {
            this.ensureTickVisible(tick, true);
        }
        this.highlightPlayingTick(tick);
        this.updatePlaybackControls();
    }

    ensureTickVisible(tick, center = false) {
        const maxStart = Math.max(0, this.maxTicks - TICKS_PER_VIEW);
        let nextStart = this.visibleTickStart;
        if (center) {
            nextStart = Math.max(0, tick - Math.floor(TICKS_PER_VIEW / 2));
        } else if (tick < this.visibleTickStart) {
            nextStart = tick;
        } else if (tick >= this.visibleTickStart + TICKS_PER_VIEW) {
            nextStart = tick - TICKS_PER_VIEW + 1;
        }
        nextStart = Math.max(0, Math.min(nextStart, maxStart));
        if (nextStart === this.visibleTickStart) {
            return false;
        }
        return this.setVisibleTickStart(nextStart);
    }

    copyNotes() {
        if (this.selectedNotes.length === 0) {
            this.showStatus('未选中音符');
            return;
        }

        this.clipboard = this.selectedNotes
            .map(note => this.getNote(note.pitch, note.tick))
            .filter(Boolean)
            .sort((a, b) => a.tick - b.tick || a.pitch - b.pitch)
            .map(note => ({
                pitch: note.pitch,
                tick: note.tick,
                instruments: [...note.instruments]
            }));

        this.showStatus(`已复制 ${this.clipboard.length} 个音符`);
    }

    pasteNotes() {
        if (this.clipboard.length === 0) {
            this.showStatus('剪贴板为空');
            return;
        }

        const minTick = Math.min(...this.clipboard.map(note => note.tick));
        const maxRelativeTick = Math.max(...this.clipboard.map(note => note.tick - minTick));
        const preferredTick = this.isPlaying ? this.currentTick : this.visibleTickStart;
        let newNotes = null;

        for (let targetTick = preferredTick; targetTick + maxRelativeTick < this.maxTickLimit; targetTick++) {
            const candidate = this.buildPasteNotes(minTick, targetTick);
            if (candidate) {
                newNotes = candidate;
                break;
            }
        }

        if (!newNotes || newNotes.length === 0) {
            this.showStatus('没有可粘贴的音符');
            return;
        }

        this.saveStateForUndo();
        this.musicData.notes.push(...newNotes);
        this.selectedNotes = newNotes.map(note => ({ pitch: note.pitch, tick: note.tick }));
        this.lastSelectedNote = this.selectedNotes[this.selectedNotes.length - 1] || null;
        this.rebuildIndexes();
        this.ensureTimelineCapacity(Math.max(...newNotes.map(note => note.tick)));
        this.markDirty(true);
        this.renderNoteGrid();
        this.updateHeader();
        this.updateSelectedNoteInfo();
        this.showStatus(`已粘贴 ${newNotes.length} 个音符`);
    }

    buildPasteNotes(sourceMinTick, targetTick) {
        const newNotes = [];
        for (const note of this.clipboard) {
            const nextTick = note.tick - sourceMinTick + targetTick;
            if (nextTick < 0 || nextTick >= this.maxTickLimit) return null;
            if (note.pitch > this.maxPitch) return null;
            const key = this.noteKey(note.pitch, nextTick);
            if (this.noteIndex.has(key)) return null;
            newNotes.push({
                pitch: note.pitch,
                tick: nextTick,
                instruments: [...note.instruments]
            });
        }
        return newNotes;
    }

    deleteSelectedNotes() {
        if (this.selectedNotes.length === 0) {
            this.showStatus('未选中音符');
            return;
        }

        const selected = new Set(this.selectedNotes.map(note => this.noteKey(note.pitch, note.tick)));
        this.saveStateForUndo();
        this.musicData.notes = this.musicData.notes.filter(note => !selected.has(this.noteKey(note.pitch, note.tick)));
        this.selectedNotes = [];
        this.rebuildIndexes();
        this.maxTicks = this.createDisplayTimelineLength(0, this.maxTickLimit);
        this.clampVisibleTick();
        this.markDirty(true);
        this.renderTimeRuler();
        this.renderNoteGrid();
        this.updateHeader();
        this.updateTimelineControls();
        this.updateSelectedNoteInfo();
        this.showStatus('已删除选中音符');
    }

    clearAllNotes() {
        if (!confirm('确定清空全部音符？')) return;

        this.saveStateForUndo();
        this.musicData.notes = [];
        this.selectedNotes = [];
        this.rebuildIndexes();
        this.markDirty(true);
        this.renderNoteGrid();
        this.updateHeader();
        this.updateSelectedNoteInfo();
        this.showStatus('已清空');
    }

    openImportDialog() {
        if (this.dirty && !confirm('当前有未保存修改，导入会覆盖当前歌曲内容。继续导入？')) {
            return;
        }

        const modal = document.getElementById('import-modal');
        const input = document.getElementById('import-path-input');
        const fileInput = document.getElementById('import-file-input');
        if (!modal || !input) return;
        input.value = '';
        if (fileInput) fileInput.value = '';
        modal.classList.remove('hidden');
        if (fileInput) {
            fileInput.focus();
        } else {
            input.focus();
        }
    }

    closeImportDialog() {
        const modal = document.getElementById('import-modal');
        if (modal) modal.classList.add('hidden');
    }

    async importMusic() {
        const input = document.getElementById('import-path-input');
        const fileInput = document.getElementById('import-file-input');
        const requestedPath = input?.value?.trim();
        const uploadFile = fileInput?.files?.[0] || null;
        if (!uploadFile && !requestedPath) {
            this.showStatus('请选择 NBS/MIDI 文件或输入服务器文件名');
            return;
        }
        if (uploadFile && !this.isSupportedImportFile(uploadFile.name)) {
            this.showStatus('仅支持 .nbs、.mid、.midi 文件');
            return;
        }

        this.showStatus('导入中...');
        try {
            let response;
            if (uploadFile) {
                const formData = new FormData();
                formData.append('file', uploadFile, uploadFile.name);
                response = await this.apiFetch('/api/import', {
                    method: 'POST',
                    body: formData
                });
            } else {
                response = await this.apiFetch('/api/import', {
                    method: 'POST',
                    headers: { 'Content-Type': 'application/json' },
                    body: JSON.stringify({ path: requestedPath })
                });
            }

            if (!response.ok) {
                if (this.handleApiUnauthorized(response)) return;
                const text = await response.text();
                this.showStatus(`导入失败 (${response.status}) ${text}`);
                return;
            }

            const result = await response.json();
            this.undoStack = [];
            this.redoStack = [];
            this.applyMusicData(result.music, { dirty: false, focusContent: true });
            localStorage.removeItem(this.getDraftKey());
            this.closeImportDialog();

            const warningCount = Array.isArray(result.warnings) ? result.warnings.length : 0;
            this.showStatus(warningCount > 0
                ? `已导入 ${result.format}，${warningCount} 条提示`
                : `已导入 ${result.format}`);
        } catch (error) {
            console.error('Import failed:', error);
            this.showStatus(`导入失败: ${error.message}`);
        }
    }

    isSupportedImportFile(fileName) {
        return /\.(nbs|mid|midi)$/i.test(fileName || '');
    }

    showDialog({ className, title, lines, buttons }) {
        const dialog = document.createElement('div');
        dialog.className = `${className} modal`;

        const content = document.createElement('div');
        content.className = 'dialog-content modal-content';

        const heading = document.createElement('h3');
        heading.textContent = title;
        content.appendChild(heading);

        lines.forEach(line => {
            const paragraph = document.createElement('p');
            paragraph.textContent = line;
            content.appendChild(paragraph);
        });

        const actions = document.createElement('div');
        actions.className = 'dialog-buttons modal-footer';
        buttons.forEach(buttonConfig => {
            const button = document.createElement('button');
            button.type = 'button';
            button.dataset.dialogAction = buttonConfig.id;
            button.className = buttonConfig.primary ? 'modal-btn confirm' : 'modal-btn cancel';
            button.textContent = buttonConfig.label;
            button.onclick = () => {
                if (typeof buttonConfig.onClick === 'function') {
                    buttonConfig.onClick(dialog);
                } else {
                    dialog.remove();
                }
            };
            actions.appendChild(button);
        });
        content.appendChild(actions);
        dialog.appendChild(content);
        document.body.appendChild(dialog);
        return dialog;
    }

    showStatus(text) {
        const status = document.getElementById('status-text');
        if (status) status.textContent = text;
    }
}

const editor = new MusicEditor();

document.addEventListener('DOMContentLoaded', () => editor.init());
