import React from 'react';
import ReactDOM from 'react-dom/client';
import {BrowserRouter} from 'react-router-dom';
import {QueryClient,QueryClientProvider} from '@tanstack/react-query';
import {AuthProvider} from './auth';
import App from './App';
import './styles.css';
async function start(){
 if(navigator.serviceWorker){for(const r of await navigator.serviceWorker.getRegistrations()){if(r.active?.scriptURL.endsWith('/mockServiceWorker.js'))await r.unregister()}}
 const client=new QueryClient({defaultOptions:{queries:{retry:1,refetchOnWindowFocus:false},mutations:{retry:false}}});
 ReactDOM.createRoot(document.getElementById('root')!).render(<React.StrictMode><QueryClientProvider client={client}><BrowserRouter><AuthProvider><App/></AuthProvider></BrowserRouter></QueryClientProvider></React.StrictMode>);
}
start().catch(e=>{document.getElementById('root')!.textContent='앱 시작 실패: '+e.message});
