import { useState } from "react";
import { useAuth } from "./hooks/useAuth";
import LandingPage from "./components/landing/LandingPage";
import AppLayout from "./components/layout/AppLayout";
import HomePage from "./components/home/HomePage";
import CardsPage from "./components/cards/CardsPage";
import OrganizeFlow from "./components/organize/OrganizeFlow";
import GithubSettingsPage from "./components/settings/GithubSettingsPage";
import AccountSettingsPage from "./components/settings/AccountSettingsPage";

const PAGES = {
  home: HomePage,
  cards: CardsPage,
  github: GithubSettingsPage,
  mypage: AccountSettingsPage,
};

export default function App() {
  const { loggedIn, login, logout } = useAuth();
  const [active, setActive] = useState("home");

  const handleLogout = () => {
    logout();
    setActive("home");
  };

  if (!loggedIn) {
    return <LandingPage onLogin={login} />;
  }

  const Page = PAGES[active];

  return (
    <AppLayout active={active === "organize" ? "organize" : active} onNavigate={setActive}>
      {active === "organize" ? (
        <OrganizeFlow onExit={() => setActive("home")} />
      ) : (
        <div key={active} className="animate-fade-in">
          <Page onNavigate={setActive} onLogout={handleLogout} />
        </div>
      )}
    </AppLayout>
  );
}
