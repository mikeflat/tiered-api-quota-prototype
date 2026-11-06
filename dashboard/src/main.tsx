import { StrictMode } from 'react'
import { createRoot } from 'react-dom/client'
import { createHttpHealthSource } from './adapters/api/httpHealthSource.ts'
import { createHttpSnapshotSource } from './adapters/api/httpSnapshotSource.ts'
import { createHttpTierAdministration } from './adapters/api/httpTierAdministration.ts'
import { browserScheduler } from './adapters/time/browserScheduler.ts'
import { DashboardPortsProvider } from './application/DashboardPorts.tsx'
import './ui/index.css'
import App from './ui/App.tsx'

const boundFetch: typeof fetch = (input, init) => fetch(input, init)

const ports = {
  snapshots: createHttpSnapshotSource(boundFetch),
  health: createHttpHealthSource(boundFetch),
  scheduler: browserScheduler,
  tierAdministration: createHttpTierAdministration(boundFetch),
}

createRoot(document.getElementById('root')!).render(
  <StrictMode>
    <DashboardPortsProvider ports={ports}>
      <App />
    </DashboardPortsProvider>
  </StrictMode>,
)
