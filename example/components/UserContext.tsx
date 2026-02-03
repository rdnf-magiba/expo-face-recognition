import React, { createContext, useState, useContext, ReactNode } from 'react';
import { EmbeddingIndex, SearchResult } from "client-vector-search";

export type RegisteredUser = {
    name: string;
    embedding: number[];
};

type UserContextType = {
    users: RegisteredUser[];
    addUser: (name: string, embedding: number[]) => void;
    identifyUser: (embedding: number[]) => Promise<{ name: string; score: number } | null>;
    threshold: number;
};

const UserContext = createContext<UserContextType | undefined>(undefined);

export const UserProvider = ({ children }: { children: ReactNode }) => {
    const [users, setUsers] = useState<RegisteredUser[]>([]);
    // Initialize the search index.
    const [index] = useState(() => new EmbeddingIndex());

    const MATCH_THRESHOLD = 0.5;

    const addUser = (name: string, embedding: number[]) => {
        // Add to vector search index. 
        // The library expects the object to contain the vector. 
        // By convention/default it is likely 'embedding' or inferred?
        // Looking at common usage, usually you pass object. 
        // We pass { name, embedding }.
        index.add({ name, embedding });

        // Update local state for UI list
        setUsers(prev => [...prev, { name, embedding }]);
    };

    const identifyUser = async (embedding: number[]): Promise<{ name: string; score: number } | null> => {
        try {
            const results: SearchResult[] = await index.search(embedding, { topK: 10 });
            if (results && results.length > 0) {
                const best = results[0];
                console.log(best.similarity)
                // best.object is the user object we stored
                if (best.similarity > MATCH_THRESHOLD) {
                    return { name: best.object.name, score: best.similarity };
                }
            }
        } catch (e) {
            console.error("Vector search error:", e);
        }
        return null;
    };

    return (
        <UserContext.Provider value={{ users, addUser, identifyUser, threshold: MATCH_THRESHOLD }}>
            {children}
        </UserContext.Provider>
    );
};

export const useUser = () => {
    const context = useContext(UserContext);
    if (!context) throw new Error("useUser must be used within a UserProvider");
    return context;
};
