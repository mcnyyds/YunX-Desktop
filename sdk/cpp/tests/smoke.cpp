#include <cstdlib>
#include <iostream>
#include "yunx_client.hpp"

static void require(bool condition, const char* message) {
    if (!condition) throw std::runtime_error(message);
}

int main() {
    try {
        const char* token = std::getenv("YUNX_HTTP_TOKEN");
        const char* port = std::getenv("YUNX_HTTP_PORT");
        require(token != nullptr, "Set YUNX_HTTP_TOKEN");
        yunx::Client client(static_cast<unsigned short>(port ? std::stoi(port) : 17890), token);
        require(client.health().at("status") == "ok", "health failed");
        require(client.platforms().at("platforms").size() == 10, "platform list failed");
        require(client.accounts().at("accounts").is_array(), "account list failed");
        const auto capabilities = client.platforms().at("platforms");
        require(capabilities.at(0).at("listFiles") == true, "drive capabilities missing");
        try {
            client.create_share({"missing-account", {"file-1", "file-2"}, "test", 7, "abcd"});
            throw std::runtime_error("missing drive account was accepted");
        } catch (const yunx::ApiError& error) {
            require(error.status == 404 && error.code == "ACCOUNT_NOT_FOUND", "array or integer serialization failed");
        }
        try {
            client.transfer_share("missing-share", {"missing-account", {"file-1"}, "0"});
            throw std::runtime_error("missing share session was accepted");
        } catch (const yunx::ApiError& error) {
            require(error.status == 404 && error.code == "SHARE_NOT_FOUND", "transfer route failed");
        }
        try {
            client.open_share({"invalid link"});
            throw std::runtime_error("invalid link was accepted");
        } catch (const yunx::ApiError& error) {
            require(error.status == 400 && error.code == "INVALID_LINK", "error decoding failed");
        }
        try {
            yunx::Client unauthorized(static_cast<unsigned short>(port ? std::stoi(port) : 17890), "wrong-token");
            unauthorized.health();
            throw std::runtime_error("wrong token was accepted");
        } catch (const yunx::ApiError& error) {
            require(error.status == 401 && error.code == "UNAUTHORIZED", "authentication failed");
        }
        std::cout << "C++ HTTP smoke passed\n";
    } catch (const std::exception& error) {
        std::cerr << error.what() << '\n';
        return 1;
    }
}
