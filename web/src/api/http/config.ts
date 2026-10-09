// IADron: la configuración se lee de env/.env.local (ignorado por git, no se suben credenciales).
// Copia env/.env.example a env/.env.local y rellénalo. Ver INSTALACION.md del repo IADron_3TD.
const env = import.meta.env as unknown as Record<string, string | undefined>
const SERVER_IP = env.VITE_SERVER_IP || 'localhost'

export const CURRENT_CONFIG = {

  // license (app Cloud API de developer.dji.com)
  appId: env.VITE_DJI_APP_ID || '',
  appKey: env.VITE_DJI_APP_KEY || '',
  appLicense: env.VITE_DJI_APP_LICENSE || '',

  // http: debe terminar en "/"
  baseURL: env.VITE_BACKEND_URL || `http://${SERVER_IP}:6789/`,
  websocketURL: env.VITE_WEBSOCKET_URL || `ws://${SERVER_IP}:6789/api/v1/ws`,

  // livestreaming (mismos valores neutros que application-iadron.yml del backend)
  rtmpURL: `rtmp://${SERVER_IP}/live/`,
  gbServerIp: SERVER_IP,
  gbServerPort: '5060',
  gbServerId: '34020000002000000001',
  gbAgentId: '34020000001320000001',
  gbPassword: 'iadron',
  gbAgentPort: '5061',
  gbAgentChannel: '34020000001320000001',
  // RTSP
  rtspUserName: 'iadron',
  rtspPassword: 'iadron',
  rtspPort: '8554',
  // Agora (no se usa)
  agoraAPPID: '',
  agoraToken: '',
  agoraChannel: 'iadron',

  // map: AMap (Gaode) sólo funciona con clave china. En interior no se usa el mapa;
  // sin clave el mapa no carga, pero el resto de la web funciona.
  amapKey: env.VITE_AMAP_KEY || '',

}
