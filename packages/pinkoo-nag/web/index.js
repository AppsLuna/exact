// @pinkoo/nag on the web: absent. No web API rings through silence or acts while the page is closed, so the status
// says the engine is not available, the state is empty, and every send is `NotSupportedError`. Under the agent the
// declared substitute answers the same.

const STATUS = {
  available: false, notifications: 'denied', channelBlocked: false, exactAlarms: false, fullScreen: false,
  overlay: false, promoted: 'unavailable', batteryUnrestricted: false, backgroundRestricted: false, standbyBucket: 0,
  alarmVolume: 0, dndAllowsAlarms: false, maker: '',
};
const absent = async () => ({ error: 'NotSupportedError' });

export function implementation() {
  return {
    async nagStatus() { return STATUS; },
    async nagState() { return { doses: [], events: [] }; },
    scheduleNags: absent,
    resolveNags: absent,
    ackNagEvents: absent,
    nagScreen: absent,
    requestNagAccess: absent,
    previewNag: absent,
  };
}
