#include <cstdlib>
#include <iostream>
#include "yunx_client.hpp"

int main() {
    const char* token = std::getenv("YUNX_HTTP_TOKEN");
    if (!token) return 1;
    try {
        yunx::Client client(17890, token);
        std::cout << client.health().dump() << '\n';
        // To open a login window: client.start_login({"quark"});
    } catch (const std::exception& error) {
        std::cerr << error.what() << '\n';
        return 1;
    }
}
