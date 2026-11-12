output "vpc_id" { value = aws_vpc.this.id }
output "subnet_ids" {
  value      = [for zone in var.availability_zones : aws_subnet.public[zone].id]
  depends_on = [aws_route_table_association.public]
}
output "contract" {
  description = "Reviewable public networking choices; no NAT gateways or SSH ingress are created."
  value = {
    subnet_count       = length(aws_subnet.public)
    availability_zones = [for zone in var.availability_zones : aws_subnet.public[zone].availability_zone]
    public_ips         = [for zone in var.availability_zones : aws_subnet.public[zone].map_public_ip_on_launch]
    default_route      = aws_route_table.public.route
    dns_support        = aws_vpc.this.enable_dns_support
    dns_hostnames      = aws_vpc.this.enable_dns_hostnames
  }
}
