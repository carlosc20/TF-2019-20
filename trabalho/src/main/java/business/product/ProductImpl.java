package business.product;

public class ProductImpl implements Product {

	private String name;
	private float price;
	private String description;

	public ProductImpl(String name, float price, String description){
		this.name = name;
		this.price = price;
		this.description = description;
	}

	@Override
	public String getName() {
		return name;
	}

	@Override
	public float getPrice() {
		return price;
	}

	@Override
	public String getDescription() {
		return description;
	}
}