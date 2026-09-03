package io.nekohasekai.sagernet.dto

data class IPAPIInfo(
    var ip: String? = null,
    var clientIp: String? = null,
    var ip_addr: String? = null,
    var query: String? = null,
    var country: String? = null,
    var country_name: String? = null,
    var country_code: String? = null,
    var countryCode: String? = null,
    var region: String? = null,
    var regionName: String? = null,
    var region_name: String? = null,
    var city: String? = null,
    var city_name: String? = null,
    var location: LocationBean? = null,
    var country_info: CountryInfoBean? = null,
    var countryInfo: CountryInfoBean? = null,
    var region_info: RegionInfoBean? = null,
    var regionInfo: RegionInfoBean? = null,
    var city_info: CityInfoBean? = null,
    var cityInfo: CityInfoBean? = null,
) {
    data class LocationBean(
        var country_code: String? = null,
        var country_name: String? = null,
        var region_name: String? = null,
        var city_name: String? = null,
    )

    data class CountryInfoBean(
        var country_code: String? = null,
        var country_name: String? = null,
        var name: String? = null,
        var code: String? = null,
    )

    data class RegionInfoBean(
        var region_name: String? = null,
        var name: String? = null,
        var code: String? = null,
    )

    data class CityInfoBean(
        var city_name: String? = null,
        var name: String? = null,
    )
}
