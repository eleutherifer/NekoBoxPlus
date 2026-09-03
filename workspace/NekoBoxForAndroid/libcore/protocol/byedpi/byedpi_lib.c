//go:build android && cgo

#include <android/log.h>

#define ANDROID_APP 1
#define STR_MODE 1
#define main byedpi_entry_main

#define add nb4a_byedpi_add
#define add_event nb4a_byedpi_add_event
#define buff_destroy nb4a_byedpi_buff_destroy
#define buff_pop nb4a_byedpi_buff_pop
#define buff_push nb4a_byedpi_buff_push
#define change_tls_sni nb4a_byedpi_change_tls_sni
#define clear_params nb4a_byedpi_clear_params
#define create_conn nb4a_byedpi_create_conn
#define data_from_str nb4a_byedpi_data_from_str
#define del_event nb4a_byedpi_del_event
#define destroy_pool nb4a_byedpi_destroy_pool
#define desync nb4a_byedpi_desync
#define desync_udp nb4a_byedpi_desync_udp
#define dump_all_cache nb4a_byedpi_dump_all_cache
#define dump_cache nb4a_byedpi_dump_cache
#define fake_http nb4a_byedpi_fake_http
#define fake_tls nb4a_byedpi_fake_tls
#define fake_udp nb4a_byedpi_fake_udp
#define ftob nb4a_byedpi_ftob
#define get_addr nb4a_byedpi_get_addr
#define get_addr_scheme nb4a_byedpi_get_addr_scheme
#define get_default_ttl nb4a_byedpi_get_default_ttl
#define http_data nb4a_byedpi_http_data
#define init nb4a_byedpi_init
#define init_pid_file nb4a_byedpi_init_pid_file
#define init_pool nb4a_byedpi_init_pool
#define ipv6_support nb4a_byedpi_ipv6_support
#define is_http nb4a_byedpi_is_http
#define is_http_redirect nb4a_byedpi_is_http_redirect
#define is_tls_chello nb4a_byedpi_is_tls_chello
#define is_tls_shello nb4a_byedpi_is_tls_shello
#define kavl_erase_my nb4a_byedpi_kavl_erase_my
#define kavl_find_my nb4a_byedpi_kavl_find_my
#define kavl_insert_my nb4a_byedpi_kavl_insert_my
#define kavl_itr_find_my nb4a_byedpi_kavl_itr_find_my
#define kavl_itr_first_my nb4a_byedpi_kavl_itr_first_my
#define kavl_itr_next_my nb4a_byedpi_kavl_itr_next_my
#define listen_socket nb4a_byedpi_listen_socket
#define load_cache nb4a_byedpi_load_cache
#define loop_event nb4a_byedpi_loop_event
#define map_fix nb4a_byedpi_map_fix
#define mem_add nb4a_byedpi_mem_add
#define mem_delete nb4a_byedpi_mem_delete
#define mem_destroy nb4a_byedpi_mem_destroy
#define mem_get nb4a_byedpi_mem_get
#define mem_pool nb4a_byedpi_mem_pool
#define mod_etype nb4a_byedpi_mod_etype
#define mod_http nb4a_byedpi_mod_http
#define neq_tls_sid nb4a_byedpi_neq_tls_sid
#define next_event nb4a_byedpi_next_event
#define next_event_tv nb4a_byedpi_next_event_tv
#define on_connect nb4a_byedpi_on_connect
#define on_connerr nb4a_byedpi_on_connerr
#define on_ignore nb4a_byedpi_on_ignore
#define on_request nb4a_byedpi_on_request
#define on_timeout nb4a_byedpi_on_timeout
#define on_trigger nb4a_byedpi_on_trigger
#define on_tunnel nb4a_byedpi_on_tunnel
#define on_udp_tunnel nb4a_byedpi_on_udp_tunnel
#define options nb4a_byedpi_options
#define params nb4a_byedpi_params
#define parse_args nb4a_byedpi_parse_args
#define parse_cform nb4a_byedpi_parse_cform
#define parse_hosts nb4a_byedpi_parse_hosts
#define parse_http nb4a_byedpi_parse_http
#define parse_ipset nb4a_byedpi_parse_ipset
#define parse_offset nb4a_byedpi_parse_offset
#define parse_tls nb4a_byedpi_parse_tls
#define part_tls nb4a_byedpi_part_tls
#define post_desync nb4a_byedpi_post_desync
#define pre_desync nb4a_byedpi_pre_desync
#define run nb4a_byedpi_run
#define server_fd nb4a_byedpi_server_fd
#define start_event_loop nb4a_byedpi_start_event_loop

#include "../../../../byedpi/main.c"
#include "../../../../byedpi/conev.c"
#include "../../../../byedpi/proxy.c"
#include "../../../../byedpi/desync.c"
#include "../../../../byedpi/mpool.c"
#include "../../../../byedpi/extend.c"
#include "../../../../byedpi/packets.c"

static const struct params byedpi_default_params = {
    .await_int = 10,
    .ipv6 = 1,
    .resolve = 1,
    .udp = 1,
    .max_open = 512,
    .bfsize = 16384,
    .baddr = {
        .in6 = { .sin6_family = AF_INET6 }
    },
    .laddr = {
        .in = { .sin_family = AF_INET }
    },
    .debug = 0
};

int byedpi_embedded_main(int argc, char **argv) {
    clear_params(NULL, NULL);
    params = byedpi_default_params;
    server_fd = 0;
    optind = 0;
    opterr = 1;
    optopt = 0;
    optarg = NULL;
    __android_log_print(ANDROID_LOG_INFO, "NB4A-ByeDPI", "embedded main reset complete");
    int result = byedpi_entry_main(argc, argv);
    __android_log_print(ANDROID_LOG_INFO, "NB4A-ByeDPI", "embedded main returned %d", result);
    return result;
}
