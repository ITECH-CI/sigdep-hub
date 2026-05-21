import { Navigate, Route, Routes } from "react-router-dom";
import { RequireAuth } from "./auth";
import { AppLayout } from "./components/AppLayout";
import { Dashboard } from "./pages/Dashboard";
import { Landing } from "./pages/Landing";
import { PatientDetail } from "./pages/PatientDetail";
import { Patients } from "./pages/Patients";
import { Sites } from "./pages/Sites";
import { Biologie } from "./pages/Biologie";
import { Pepfar } from "./pages/Pepfar";
import { Tpt } from "./pages/Tpt";
import { Clinique } from "./pages/Clinique";
import { Pharmacie } from "./pages/Pharmacie";
import { Depistage } from "./pages/Depistage";
import { Ptme } from "./pages/Ptme";
import { Synchronisation } from "./pages/Synchronisation";
import { Utilisateurs } from "./pages/Utilisateurs";

export function App() {
  return (
    <Routes>
      {/* Public */}
      <Route path="/" element={<Landing />} />
      <Route path="/public" element={<Landing />} />

      {/* Authenticated app — /app/* */}
      <Route
        path="/app"
        element={
          <RequireAuth>
            <AppLayout />
          </RequireAuth>
        }
      >
        <Route index element={<Navigate to="vue-ensemble" replace />} />
        <Route path="vue-ensemble" element={<Dashboard />} />
        <Route path="pepfar" element={<Pepfar />} />
        <Route path="patients" element={<Patients />} />
        <Route path="patients/:id" element={<PatientDetail />} />
        <Route path="sites" element={<Sites />} />
        <Route path="clinique" element={<Clinique />} />
        <Route path="pharmacie" element={<Pharmacie />} />
        <Route path="depistage" element={<Depistage />} />
        <Route path="ptme" element={<Ptme />} />
        <Route path="tpt" element={<Tpt />} />
        <Route path="biologie" element={<Biologie />} />
        <Route path="sync" element={<Synchronisation />} />
        <Route path="users" element={<Utilisateurs />} />
      </Route>

      <Route path="*" element={<Navigate to="/" replace />} />
    </Routes>
  );
}
