package vttp2022.mp2.shop.server.repositories;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataAccessException;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.jdbc.core.BeanPropertyRowMapper;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.ResultSetExtractor;
import org.springframework.jdbc.support.rowset.SqlRowSet;
import org.springframework.stereotype.Repository;
import org.springframework.util.StringUtils;

import vttp2022.mp2.shop.server.models.ImageModel;
import vttp2022.mp2.shop.server.models.Product;

import static vttp2022.mp2.shop.server.repositories.Queries.*;

import java.io.IOException;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

@Repository
public class ProductRepository {
    
    @Autowired
    private JdbcTemplate jdbcTemplate;

    // LATEST WORKING VERSION
    public Product save(Product product) throws SQLException, IOException {

        // Check if product exists in the database
        String findProductByIdQuery = "SELECT * FROM product WHERE product_id=?";
        List<Product> products = jdbcTemplate.query(findProductByIdQuery, new BeanPropertyRowMapper<>(Product.class), product.getProductId());
    
        if (products.size() == 0) {
            // Product does not exist, insert a new row
            int rowsAffected = jdbcTemplate.update(SQL_ADD_NEW_PRODUCT, product.getProductName(), product.getProductDescription(), product.getProductDiscountedPrice(), product.getProductActualPrice());
            if (rowsAffected == 0) {
                throw new SQLException("Creating product failed, no rows affected.");
            }

            //Product image save part
            // Retrieve the product_id of the newly added product
            // gives invalid column name error but can ignore
            Long productId = jdbcTemplate.queryForObject("SELECT LAST_INSERT_ID()", Long.class);

            // Save each image to image_model table and add relation between product and image in product_images table
            for (ImageModel image : product.getProductImages()) {
                jdbcTemplate.update("INSERT INTO image_model (name, type, picByte) VALUES (?, ?, ?)", image.getName(), image.getType(), image.getPicByte());

                // gives invalid column name error but can ignore
                Long imageId = jdbcTemplate.queryForObject("SELECT LAST_INSERT_ID()", Long.class);

                jdbcTemplate.update("INSERT INTO product_images (product_id, image_id) VALUES (?, ?)", productId, imageId);
            }

            //
            SqlRowSet rs = jdbcTemplate.queryForRowSet(SQL_FIND_PRODUCT_BY_NAME, product.getProductName());
            if (!rs.next())
			    // return Optional.empty();
                throw new SQLException("Creating product failed, no ID obtained.");
		    return Product.create(rs);

        } else {
            // Product exists, update the row
            int rowsAffected = jdbcTemplate.update(SQL_UPDATE_PRODUCT, product.getProductName(), product.getProductDescription(), product.getProductDiscountedPrice(), product.getProductActualPrice(), product.getProductId());
            if (rowsAffected == 0) {
                throw new SQLException("Updating product failed, no rows affected.");
            }
    
            // Delete all existing image entries for the product
            jdbcTemplate.update("DELETE FROM product_images WHERE product_id=?", product.getProductId());
    
            // Delete all image entries that are not associated with any product
            jdbcTemplate.update("DELETE FROM image_model WHERE id NOT IN (SELECT DISTINCT image_id FROM product_images)");
        }
    
        // Retrieve the product_id of the newly added/updated product
        Integer intProductId = product.getProductId();
        Long productId = intProductId.longValue();
        if (productId == null) {
            productId = jdbcTemplate.queryForObject("SELECT LAST_INSERT_ID()", Long.class);
        }
    
        // Save each image to image_model table and add relation between product and image in product_images table
        for (ImageModel image : product.getProductImages()) {
            jdbcTemplate.update("INSERT INTO image_model (name, type, picByte) VALUES (?, ?, ?)", image.getName(), image.getType(), image.getPicByte());
    
            Long imageId = jdbcTemplate.queryForObject("SELECT LAST_INSERT_ID()", Long.class);
    
            jdbcTemplate.update("INSERT INTO product_images (product_id, image_id) VALUES (?, ?)", productId, imageId);
        }
    
        // Retrieve the newly added/updated product and return it
        SqlRowSet rs = jdbcTemplate.queryForRowSet(SQL_FIND_PRODUCT_BY_ID, productId);
        if (!rs.next()) {
            throw new SQLException("Creating product failed, no ID obtained.");
        }
        return Product.create(rs);
    }
    

    // LATEST WORKING VERSION
    public Page<Product> findAll(Pageable pageable) {
        String sql = "SELECT p.product_id, p.product_name, p.product_description, p.product_discounted_price, " +
                "p.product_actual_price, i.id, i.name, i.type, i.picByte " +
                "FROM product p " +
                "LEFT JOIN product_images pi ON p.product_id = pi.product_id " +
                "LEFT JOIN image_model i ON pi.image_id = i.id";
    
        List<Product> products = jdbcTemplate.query(sql, new ResultSetExtractor<List<Product>>() {
            @Override
            public List<Product> extractData(ResultSet rs) throws SQLException, DataAccessException {
                Map<Integer, Product> productMap = new HashMap<>();
    
                while (rs.next()) {
                    Integer productId = rs.getInt("product_id");
                    Product product = productMap.get(productId);
    
                    if (product == null) {
                        product = new Product();
                        product.setProductId(productId);
                        product.setProductName(rs.getString("product_name"));
                        product.setProductDescription(rs.getString("product_description"));
                        product.setProductDiscountedPrice(rs.getDouble("product_discounted_price"));
                        product.setProductActualPrice(rs.getDouble("product_actual_price"));
    
                        product.setProductImages(new HashSet<>());
    
                        productMap.put(productId, product);
                    }
    
                    ImageModel image = new ImageModel();
                    image.setId(rs.getLong("id"));
                    image.setName(rs.getString("name"));
                    image.setType(rs.getString("type"));
                    image.setPicByte(rs.getBytes("picByte"));
    
                    product.getProductImages().add(image);
                }
    
                return new ArrayList<>(productMap.values());
            }
        });
    
        int pageSize = pageable.getPageSize();
        int currentPage = pageable.getPageNumber();
        int startItem = currentPage * pageSize;
        List<Product> list;
    
        if (products.size() < startItem) {
            list = Collections.emptyList();
        } else {
            int toIndex = Math.min(startItem + pageSize, products.size());
            list = products.subList(startItem, toIndex);
        }
    
        return new PageImpl<>(list, PageRequest.of(currentPage, pageSize), products.size());
    }


    public Page<Product> findByProductNameContainingIgnoreCaseOrProductDescriptionContainingIgnoreCase(
        String key1, String key2, Pageable pageable) {

        String sql = "SELECT p.product_id, p.product_name, p.product_description, p.product_discounted_price, " +
                "p.product_actual_price, i.id, i.name, i.type, i.picByte " +
                "FROM product p " +
                "LEFT JOIN product_images pi ON p.product_id = pi.product_id " +
                "LEFT JOIN image_model i ON pi.image_id = i.id " +
                "WHERE LOWER(p.product_name) LIKE LOWER(?) OR LOWER(p.product_description) LIKE LOWER(?)";
        
        String searchKey1 = "%" + key1.toLowerCase() + "%";
        String searchKey2 = "%" + key2.toLowerCase() + "%";
        int limit = pageable.getPageSize();

        List<Product> products = jdbcTemplate.query(sql, ps -> {
            ps.setString(1, searchKey1);
            ps.setString(2, searchKey2);
        }, (rs, rowNum) -> {
            Integer productId = rs.getInt("product_id");
            Product product = new Product();
            product.setProductId(productId);
            product.setProductName(rs.getString("product_name"));
            product.setProductDescription(rs.getString("product_description"));
            product.setProductDiscountedPrice(rs.getDouble("product_discounted_price"));
            product.setProductActualPrice(rs.getDouble("product_actual_price"));

            product.setProductImages(new HashSet<>());

            ImageModel image = new ImageModel();
            image.setId(rs.getLong("id"));
            image.setName(rs.getString("name"));
            image.setType(rs.getString("type"));
            image.setPicByte(rs.getBytes("picByte"));

            product.getProductImages().add(image);

            return product;
        });
        
        int total = products.size();
        int startItem = Math.toIntExact(pageable.getOffset());
        int toIndex = Math.min(startItem + limit, total);
        List<Product> list;
        
        if (startItem >= products.size()) {
            list = Collections.emptyList();
        } else {
            list = products.subList(startItem, toIndex);
        }

        return new PageImpl<>(list, PageRequest.of(pageable.getPageNumber(), limit), total);
    }
    

    public void deleteById(Integer productId) throws SQLException {
        // Get the IDs of the image models associated with the product being deleted
        List<Long> imageModelIds = jdbcTemplate.queryForList("SELECT image_id FROM product_images WHERE product_id = ?", Long.class, productId);
    
        // Delete records from product_images table for the product to be deleted
        jdbcTemplate.update("DELETE FROM product_images WHERE product_id = ?", productId);
    
        // Delete records from image_model table for the image models associated with the product being deleted
        jdbcTemplate.update("DELETE FROM image_model WHERE id IN (" + StringUtils.collectionToCommaDelimitedString(imageModelIds) + ")");
    
        // Delete the product from the product table
        int rowsAffected = jdbcTemplate.update(SQL_DELETE_PRODUCT, productId);
    
        if (rowsAffected == 0) {
            throw new SQLException("Deleting product failed, no rows affected.");
        }

        //Known bug is that order_detail has record to product so cannot delete
    }

    public Product findById(Integer productId) throws SQLException {
        String sql = "SELECT p.product_id, p.product_name, p.product_description, " +
                "p.product_discounted_price, p.product_actual_price, i.id, i.name, i.type, i.picByte " +
                "FROM product p " +
                "INNER JOIN product_images pi ON p.product_id = pi.product_id " +
                "INNER JOIN image_model i ON pi.image_id = i.id " +
                "WHERE p.product_id = ?";
    
        SqlRowSet rs = jdbcTemplate.queryForRowSet(sql, productId);
    
        Product product = null;
        Set<ImageModel> images = new HashSet<>();
    
        while (rs.next()) {
            if (product == null) {
                product = new Product();
                product.setProductId(rs.getInt("product_id"));
                product.setProductName(rs.getString("product_name"));
                product.setProductDescription(rs.getString("product_description"));
                product.setProductDiscountedPrice(rs.getDouble("product_discounted_price"));
                product.setProductActualPrice(rs.getDouble("product_actual_price"));
            }
    
            ImageModel image = new ImageModel();
            image.setId(rs.getLong("id"));
            image.setName(rs.getString("name"));
            image.setType(rs.getString("type"));
    
            Object obj = rs.getObject("picByte");
            if (obj != null) {
                if (obj instanceof byte[]) {
                    image.setPicByte((byte[]) obj);
                } else {
                    throw new SQLException("Invalid column type for column 'picByte'");
                }
            }
    
            images.add(image);
        }
    
        if (product != null) {
            product.setProductImages(images);
        }
    
        return product;
    }

    
    
}
