const SAN_JOSE_CENTER = [37.335, -121.89];
const MIN_ZOOM_FOR_DRIVERS = 12;

const map = L.map('map').setView(SAN_JOSE_CENTER, 13);
L.tileLayer('https://{s}.tile.openstreetmap.org/{z}/{x}/{y}.png', {
    attribution: '© OpenStreetMap contributors',
    maxZoom: 19
}).addTo(map);

const statusEl = document.getElementById('status');
const countEl = document.getElementById('count');
const simBtn = document.getElementById('simBtn');

async function fetchSimulatorStatus() {
    const res = await fetch('/api/v1/driver/simulator');
    const text = await res.text();
    setSimBtn(text.trim() === 'running');
}

function setSimBtn(running) {
    simBtn.textContent = running ? 'Stop Simulator' : 'Start Simulator';
    simBtn.style.background = running ? '#c0392b' : '#27ae60';
    simBtn.style.color = '#fff';
}

async function toggleSimulator() {
    const isRunning = simBtn.textContent === 'Stop Simulator';
    const action = isRunning ? 'stop' : 'start';
    simBtn.disabled = true;
    await fetch(`/api/v1/driver/simulator/${action}`, { method: 'POST' });
    setSimBtn(!isRunning);
    simBtn.disabled = false;
}

fetchSimulatorStatus();

// --- Driver markers ---

const markers = {};

const driverIcon = L.divIcon({
    className: '',
    html: '<div style="font-size:16px;line-height:1;">🚗</div>',
    iconSize: [16, 16],
    iconAnchor: [8, 8]
});

function makeMarker(lat, lng) {
    return L.marker([lat, lng], { icon: driverIcon });
}

// --- STOMP client ---

const client = new StompJs.Client({
    webSocketFactory: () => new SockJS('/driver/ws'),
    reconnectDelay: 3000,
    onConnect: () => {
        statusEl.textContent = 'Connected';
        client.subscribe('/user/queue/driver/positions', (msg) => {
            handlePositions(JSON.parse(msg.body));
        });
        client.subscribe('/user/queue/driver/membership', (msg) => {
            handleMembership(JSON.parse(msg.body));
        });
        sendViewport();
    },
    onDisconnect: () => {
        statusEl.textContent = 'Disconnected — reconnecting…';
    },
    onStompError: (frame) => {
        statusEl.textContent = 'Error: ' + frame.headers['message'];
    }
});

client.activate();

// --- Viewport ---

function sendViewport() {
    if (!client.connected) return;
    const zoom = map.getZoom();
    const bounds = map.getBounds();
    client.publish({
        destination: '/app/driver/viewport',
        body: JSON.stringify({
            minLat: bounds.getSouth(),
            maxLat: bounds.getNorth(),
            minLng: bounds.getWest(),
            maxLng: bounds.getEast(),
            zoom: zoom
        })
    });
    if (zoom < MIN_ZOOM_FOR_DRIVERS) {
        clearAllMarkers();
        countEl.textContent = 'Zoom in to see drivers';
    }
}

let viewportTimer = null;
function scheduleViewportUpdate() {
    clearTimeout(viewportTimer);
    viewportTimer = setTimeout(sendViewport, 150);
}

map.on('moveend', scheduleViewportUpdate);
map.on('zoomend', scheduleViewportUpdate);

// --- Driver message handlers ---

function handlePositions(msg) {
    const bounds = map.getBounds();
    msg.drivers.forEach(d => {
        const m = markers[d.id];
        if (!m) return;
        if (bounds.contains([d.lat, d.lng])) {
            m.setLatLng([d.lat, d.lng]);
        } else {
            m.remove();
            delete markers[d.id];
        }
    });
    updateCount();
}

function handleMembership(msg) {
    (msg.entered || []).forEach(d => {
        if (markers[d.id]) return;
        const m = makeMarker(d.lat, d.lng).addTo(map);
        markers[d.id] = m;
    });
    (msg.left || []).forEach(id => {
        const m = markers[id];
        if (m) { m.remove(); delete markers[id]; }
    });
    updateCount();
}

function clearAllMarkers() {
    Object.values(markers).forEach(m => m.remove());
    Object.keys(markers).forEach(k => delete markers[k]);
    updateCount();
}

function updateCount() {
    const n = Object.keys(markers).length;
    countEl.textContent = n > 0 ? `${n} drivers in view` : '';
}

// --- Route planning ---

let activeMode = null;      // 'origin' | 'destination' | null
let originMarker = null;
let destMarker = null;
let routeLayers = [];
let dotLayer = null;
let selectedRouteIndex = 0;
let routeData = [];

const ROUTE_COLORS = ['#2980b9', '#e67e22', '#27ae60'];

const btnOrigin = document.getElementById('btnOrigin');
const btnDest = document.getElementById('btnDest');
const btnCalc = document.getElementById('btnCalc');
const hintEl = document.getElementById('route-hint');
const routeOptionsEl = document.getElementById('route-options');

const originIcon = L.divIcon({
    className: '',
    html: '<div style="width:14px;height:14px;background:#27ae60;border:2px solid #fff;border-radius:50%;"></div>',
    iconSize: [14, 14],
    iconAnchor: [7, 7]
});

const destIcon = L.divIcon({
    className: '',
    html: '<div style="width:14px;height:14px;background:#c0392b;border:2px solid #fff;border-radius:50%;"></div>',
    iconSize: [14, 14],
    iconAnchor: [7, 7]
});

function setMode(mode) {
    activeMode = mode;
    btnOrigin.classList.toggle('active', mode === 'origin');
    btnDest.classList.toggle('active', mode === 'destination');
    hintEl.textContent = mode === 'origin' ? 'Click map to set origin' : 'Click map to set destination';
}

map.on('click', (e) => {
    if (!activeMode) return;
    const { lat, lng } = e.latlng;
    if (activeMode === 'origin') {
        if (originMarker) originMarker.remove();
        originMarker = L.marker([lat, lng], { icon: originIcon }).addTo(map);
    } else {
        if (destMarker) destMarker.remove();
        destMarker = L.marker([lat, lng], { icon: destIcon }).addTo(map);
    }
    btnCalc.disabled = !(originMarker && destMarker);
});

async function calculateRoute() {
    if (!originMarker || !destMarker) return;
    btnCalc.disabled = true;
    hintEl.textContent = 'Calculating…';
    clearRouteLayers();

    const { lat: fromLat, lng: fromLng } = originMarker.getLatLng();
    const { lat: toLat, lng: toLng } = destMarker.getLatLng();

    try {
        const res = await fetch(
            `/api/v1/route?fromLat=${fromLat}&fromLng=${fromLng}&toLat=${toLat}&toLng=${toLng}`
        );
        const data = await res.json();
        console.log('route response', JSON.stringify(data).slice(0, 300));
        routeData = data.routes || [];
        if (routeData.length === 0) {
            hintEl.textContent = 'No route found';
            return;
        }
        renderRoutes();
        selectRoute(0);
    } catch (e) {
        hintEl.textContent = 'Route calculation failed';
    } finally {
        btnCalc.disabled = false;
    }
}

function renderRoutes() {
    clearRouteLayers();
    console.log('rendering', routeData.length, 'routes');
    routeData.forEach((route, i) => {
        console.log('route', i, 'coords count:', route.coordinates?.length, 'sample:', route.coordinates?.[0]);
        const layer = L.polyline(route.coordinates, {
            color: i === 0 ? ROUTE_COLORS[i % ROUTE_COLORS.length] : '#95a5a6',
            weight: i === 0 ? 6 : 4,
            opacity: 0.85
        }).addTo(map);
        layer.on('click', () => selectRoute(i));
        routeLayers.push(layer);
    });
    console.log('routeLayers built:', routeLayers.length);
    map.fitBounds(routeLayers[0].getBounds(), { padding: [40, 40] });
    renderRouteOptions();
}

function selectRoute(index) {
    selectedRouteIndex = index;
    routeLayers.forEach((layer, i) => {
        layer.setStyle({
            color: i === index ? ROUTE_COLORS[index % ROUTE_COLORS.length] : '#95a5a6',
            weight: i === index ? 6 : 4
        });
        if (i === index) layer.bringToFront();
    });
    renderDots(index);
    renderRouteOptions();
}

function renderDots(index) {
    if (dotLayer) { dotLayer.remove(); dotLayer = null; }
    const color = ROUTE_COLORS[index % ROUTE_COLORS.length];
    const coords = routeData[index]?.coordinates || [];
    dotLayer = L.layerGroup(
        coords.map(c => L.circleMarker(c, {
            radius: 3,
            color: color,
            fillColor: '#fff',
            fillOpacity: 1,
            weight: 1.5
        }))
    ).addTo(map);
}

function renderRouteOptions() {
    routeOptionsEl.innerHTML = '';
    routeData.forEach((route, i) => {
        const mins = route.durationSeconds != null ? Math.round(route.durationSeconds / 60) + ' min' : '—';
        const km = route.distanceMeters != null ? (route.distanceMeters / 1000).toFixed(1) + ' km' : '—';
        const div = document.createElement('div');
        div.className = 'route-option' + (i === selectedRouteIndex ? ' selected' : '');
        div.style.borderLeft = `3px solid ${ROUTE_COLORS[i % ROUTE_COLORS.length]}`;
        div.textContent = `Route ${i + 1}: ${mins} · ${km}`;
        div.onclick = () => selectRoute(i);
        routeOptionsEl.appendChild(div);
    });
    hintEl.textContent = '';
}

function clearRouteLayers() {
    routeLayers.forEach(l => l.remove());
    routeLayers = [];
    if (dotLayer) { dotLayer.remove(); dotLayer = null; }
    routeOptionsEl.innerHTML = '';
}

function clearAll() {
    if (originMarker) { originMarker.remove(); originMarker = null; }
    if (destMarker) { destMarker.remove(); destMarker = null; }
    clearRouteLayers();
    routeData = [];
    activeMode = null;
    btnOrigin.classList.remove('active');
    btnDest.classList.remove('active');
    btnCalc.disabled = true;
    hintEl.textContent = '';
}
