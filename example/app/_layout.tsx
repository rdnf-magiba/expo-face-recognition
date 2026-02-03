import { Stack } from 'expo-router';
import { UserProvider } from '../components/UserContext';

export default function RootLayout() {
    return (
        <UserProvider>
            <Stack
                screenOptions={{
                    headerStyle: {
                        backgroundColor: '#000',
                    },
                    headerTintColor: '#fff',
                    headerTitleStyle: {
                        fontWeight: 'bold',
                    },
                }}
            >
                <Stack.Screen name="index" options={{ title: 'Face Recognition' }} />
                <Stack.Screen name="register" options={{ title: 'Register User' }} />
            </Stack>
        </UserProvider>
    );
}
