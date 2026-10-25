import { useEffect, useRef, useState } from 'react';
import type { ServerMessage, Subscription } from '../api/types';
import { defaultSocketUrl, TelemetrySocket, type SocketState } from './TelemetrySocket';

/**
 * Una conexión por componente que la usa. `subscription` se compara por valor: cambiarla envía
 * un `subscribe` nuevo por la misma conexión (el servidor reemplaza la anterior).
 */
export function useTelemetrySocket(subscription: Subscription | null, onMessage: (m: ServerMessage) => void): SocketState {
  const [state, setState] = useState<SocketState>('closed');
  const socketRef = useRef<TelemetrySocket | null>(null);
  const handlerRef = useRef(onMessage);
  handlerRef.current = onMessage;

  useEffect(() => {
    const socket = new TelemetrySocket(defaultSocketUrl());
    socketRef.current = socket;
    const offState = socket.onState(setState);
    const offMsg = socket.onMessage((m) => handlerRef.current(m));
    socket.connect();
    return () => {
      offState();
      offMsg();
      socket.close();
    };
  }, []);

  const key = subscription ? JSON.stringify(subscription) : '';
  useEffect(() => {
    if (subscription) socketRef.current?.subscribe(subscription);
  }, [key]);

  return state;
}
