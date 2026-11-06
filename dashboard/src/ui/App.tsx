import { useDashboardScreen } from '../application/useDashboardScreen.ts'
import { DashboardScreen } from './components/DashboardScreen.tsx'

function App() {
  const { view, grid, filters, tenantActions, changeTier } = useDashboardScreen()
  return (
    <DashboardScreen
      view={view}
      tenants={{ grid, filters, actions: tenantActions, onTierChange: changeTier }}
    />
  )
}

export default App
