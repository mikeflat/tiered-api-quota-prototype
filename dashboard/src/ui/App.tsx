import { useDashboard } from '../application/useDashboard.ts'
import { useTenantGrid } from '../application/useTenantGrid.ts'
import { DashboardScreen } from './components/DashboardScreen.tsx'

function App() {
  const view = useDashboard()
  const { grid, changeTier } = useTenantGrid(view)
  return <DashboardScreen view={view} grid={grid} onTierChange={changeTier} />
}

export default App
