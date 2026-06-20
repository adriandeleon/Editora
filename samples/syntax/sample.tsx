import React from "react";

type Props = { label: string; count: number };

export const Badge = ({ label, count }: Props) => (
  <span className="badge">
    {label}: <strong>{count}</strong>
  </span>
);
